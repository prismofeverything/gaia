(ns gaia.sync
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [cheshire.core :as json]
   [protograph.template :as template]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]
   [gaia.flow :as flow]
   [gaia.command :as command]
   [gaia.store :as store]
   [gaia.executor :as executor]))

(defn generate-sync
  [workflow kafka store]
  (let [flow (flow/generate-flow [])]
    {:workflow workflow
     :flow (atom flow)
     :commands (atom {})
     :store store
     :events (:producer kafka)
     :status
     (atom
      {:state :initialized
       :data {}})
     :tasks (agent {})}))

(def running-states
  #{:running :error})

(defn find-running
  [tasks]
  (into
   {}
   (filter
    (fn [[key task]]
      (running-states (:state task)))
    tasks)))

(defn send-tasks!
  [workflow executor store commands prior tasks]
  (log/debug! "PRIOR" prior)
  (log/debug! "TASKS" tasks)
  (let [running (find-running prior)
        relevant (remove (comp (partial get running) first) tasks)
        submit! (partial executor/submit! executor commands)
        triggered (into
                   {}
                   (map
                    (fn [[key task]]
                      [key
                       (submit!
                        (assoc task :workflow workflow))])
                    relevant))]
    (log/debug! "RUNNING" (mapv first running))
    (log/debug! "RELEVANT" (mapv first relevant))
    (log/debug! "TRIGGERED" (mapv first triggered))
    (merge triggered prior)))

(defn reset-tasks!
  [{:keys [tasks] :as state} status reset]
  (send tasks (partial apply dissoc) reset))

(defn compute-outputs
  [step]
  (into
   {}
   (map
    (fn [k]
      [k {:state :computing}])
    (vals (:outputs step)))))

(defn complete?
  [datum]
  (= :complete (keyword (:state datum))))

(defn complete-keys
  [data]
  (set
   (map
    first
    (filter
     (fn [[_ datum]]
       (complete? datum))
     data))))

(defn missing-data
  [flow data]
  (let [complete (complete-keys data)
        space (set (keys (flow/data-map flow)))]
    (set/difference space complete)))

(defn activate-front!
  [{:keys [workflow store tasks] :as state} flow executor commands status]
  (let [complete (complete-keys (:data status))
        front (mapv identity (flow/imminent-front flow complete))]
    (log/debug! "COMPLETE KEYS" (sort complete))
    (log/debug! "FRONT" front)
    (log/debug! "WAITING FOR" (flow/missing-data flow complete))
    (if (empty? front)
      (let [missing (missing-data flow (:data status))]
        (log/debug! "empty front - missing" missing)
        (assoc status :state (if (empty? missing) :complete :incomplete)))
      (let [launching (flow/step-map flow front)]
        (log/debug! "LAUNCHING" launching)
        (send tasks (partial send-tasks! workflow executor store commands) launching)
        (-> status
            (assoc :state :running))))))

(defn complete-key
  [event status]
  (assoc-in
   status [:data (:key event)]
   (merge
    (select-keys event [:workflow :path :key])
    {:url (:key event)
     :state :complete})))

(defn find-task
  [tasks id]
  (first
   (filter
    (fn [[key task]]
      (= id (:id task)))
    tasks)))

(defn step-state!
  [{:keys [tasks]} event state]
  (send
   tasks
   (fn [all task]
     (if-let [found (find-task all (:id task))]
       (do
         (log/debug! "FOUND" found)
         (-> all
             (assoc-in [(first found) :state] state)
             (assoc-in [(first found) :event] event)))
       all))
   event))

(defn data-complete!
  [{:keys [workflow flow commands status events] :as state} executor event]
  (if (= :halted (:state @status))
    (swap! status update :data dissoc (:key event))
    (do
      (swap!
       status
       (comp
        (partial activate-front! state @flow executor @commands)
        (partial complete-key event)))
      (condp = (:state @status)

        :complete
        (executor/declare-event!
         events
         {:event "flow-complete"
          :workflow workflow}
         "WORKFLOW COMPLETE")

        :incomplete
        (executor/declare-event!
         events
         {:event "flow-incomplete"
          :workflow workflow}
         "WORKFLOW STALLED")

        (log/debug! "WORKFLOW CONTINUES" workflow)))))

(defn executor-events!
  [{:keys [status workflow] :as state}
   executor topic event]
  (log/debug! "EXECUTOR EVENT" event)
  (when (= (:workflow event) (name workflow))
    (condp = (:event event)

      "step-complete"
      (step-state! state event :complete)

      "step-error"
      (step-state! state event :error)

      "task-error"
      (step-state! state event :exception)

      "data-complete"
      (data-complete! state executor event)

      "container-create"
      ()

      "execution-start"
      ()

      "execution-complete"
      ()

      "container-exit"
      ()

      (log/warn! "UNKNOWN EVENT" (:event event)))))

(defn initial-key
  [key]
  [key {:state :complete}])

(defn events-listener!
  [state executor kafka]
  (let [status-topic (get kafka :status-topic "gaia-status")
        topics ["gaia-events" status-topic]
        kafka (update kafka :subscribe concat topics)
        handle (partial executor-events! state executor)]
    (kafka/boot-consumer kafka handle)))

(defn executor-cancel!
  [executor tasks outstanding]
  (let [potential (select-keys tasks outstanding)
        canceling (find-running potential)
        expunge (keys canceling)]
    (log/info! "CANCELING" expunge)
    (doseq [[key cancel] canceling]
      (executor/cancel! executor (:id cancel)))
    (apply dissoc tasks expunge)))

(defn cancel-tasks!
  [tasks executor canceling]
  (send tasks (partial executor-cancel! executor) canceling))

(defn initialize-flow!
  [workflow store executor kafka]
  (let [flow (generate-sync workflow kafka store)
        listener (events-listener! flow executor kafka)]
    flow))

(defn find-existing
  [store flow status]
  (let [data (flow/data-nodes flow)
        [complete missing] (store/partition-data store data)
        existing (into {} (map initial-key complete))]
    (assoc status :data existing)))

(defn run-flow!
  [{:keys [workflow flow commands store status] :as state} executor]
  (when (not= :running (:state @status))
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
        (partial find-existing store now))))))

(defn dissoc-seq
  [m s]
  (apply dissoc m s))

(defn expunge-keys
  [descendants status]
  (update status :data dissoc-seq descendants))

(defn expire-keys!
  [{:keys [flow commands store status tasks] :as state} executor expiring]
  (let [now (deref flow)
        {:keys [data step] :as down} (flow/find-descendants now expiring)]
    (log/debug! "DESCENDANTS" down)
    (cancel-tasks! tasks executor step)
    (swap!
     status
     (comp
      (partial activate-front! state now executor @commands)
      (partial expunge-keys data)
      (partial find-existing store now)))
    (log/debug! "EXPIRED" down)
    down))

(defn halt-flow!
  [{:keys [workflow flow tasks status store events] :as state} executor]
  (let [now @flow
        halting (flow/step-map now)]
    (cancel-tasks! tasks executor (keys halting))
    (swap!
     status
     (comp
      (partial find-existing store now)
      #(assoc % :state :halted)))
    (executor/declare-event!
     events
     {:event "flow-halted"
      :workflow workflow}
     "WORKFLOW HALTED")))

(defn merge-steps!
  [{:keys [flow commands status tasks store] :as state} executor steps]
  (let [transform (command/transform-steps @commands steps)]
    (cancel-tasks! tasks executor (keys transform))
    (swap! flow #(flow/merge-steps % (vals transform)))
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
        (partial find-existing store now))))))

(defn expire-commands!
  [{:keys [flow commands] :as state} executor expiring]
  (let [steps (template/map-cat (partial flow/command-steps @flow) expiring)]
    (log/debug! "expiring steps" steps "from commands" (into [] expiring))
    (expire-keys! state executor steps)))

(defn merge-commands!
  [{:keys [flow commands status tasks store] :as state} executor merging]
  (log/debug! "COMMANDS" (keys merging))
  (swap! commands merge merging)
  (try
    (expire-commands! state executor (keys merging))
    (catch Exception e
      (log/exception! e "merge-commands"))))
