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
  [root kafka store]
  (let [flow (flow/generate-flow [])]
    {:root root
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
  [root executor store commands prior tasks]
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
                        (assoc task :root root))])
                    relevant))]
    (log/debug! "RUNNING" (mapv first running))
    (log/debug! "RELEVANT" (mapv first relevant))
    (log/debug! "TRIGGERED" (mapv first triggered))
    (merge triggered prior)))

(defn reset-tasks!
  [{:keys [tasks] :as state} status reset]
  (send tasks (partial apply dissoc) reset))

(defn compute-outputs
  [process]
  (into
   {}
   (map
    (fn [k]
      [k {:state :computing}])
    (vals (:outputs process)))))

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
  [{:keys [root store tasks] :as state} flow executor commands status]
  (let [complete (complete-keys (:data status))
        front (mapv identity (flow/imminent-front flow complete))]
    (log/debug! "COMPLETE KEYS" (sort complete))
    (log/debug! "FRONT" front)
    (log/debug! "WAITING FOR" (flow/missing-data flow complete))
    (if (empty? front)
      (let [missing (missing-data flow (:data status))]
        (log/debug! "empty front - missing" missing)
        (assoc status :state (if (empty? missing) :complete :incomplete)))
      (let [launching (flow/process-map flow front)]
        (log/debug! "LAUNCHING" launching)
        (send tasks (partial send-tasks! root executor store commands) launching)
        (-> status
            (assoc :state :running))))))

(defn complete-key
  [event status]
  (assoc-in
   status [:data (:key event)]
   (merge
    (select-keys event [:root :path :key])
    {:url (:key event)
     :state :complete})))

(defn find-task
  [tasks id]
  (first
   (filter
    (fn [[key task]]
      (= id (:id task)))
    tasks)))

(defn process-state!
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
  [{:keys [root flow commands status events] :as state} executor event]
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
          :root root})

        :incomplete
        (executor/declare-event!
         events
         {:event "flow-incomplete"
          :root root})

        (log/debug! "FLOW CONTINUES" root)))))

(defn executor-events!
  [{:keys [status root] :as state}
   executor topic event]
  (log/debug! "EXECUTOR EVENT" event)
  (when (= (:root event) (name root))
    (condp = (:event event)

      "process-complete"
      (process-state! state event :complete)

      "process-error"
      (process-state! state event :error)

      "task-error"
      (process-state! state event :exception)

      "data-complete"
      (data-complete! state executor event)

      (log/info! "UNKNOWN EVENT" (:event event)))))

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
  [root store executor kafka]
  (let [flow (generate-sync root kafka store)
        listener (events-listener! flow executor kafka)]
    flow))

(defn find-existing
  [store flow status]
  (let [data (flow/data-nodes flow)
        [complete missing] (store/partition-data store data)
        existing (into {} (map initial-key complete))]
    (assoc status :data existing)))

(defn trigger-flow!
  [{:keys [root flow commands store status] :as state} executor]
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
        {:keys [data process] :as down} (flow/find-descendants now expiring)]
    (log/debug! "DESCENDANTS" down)
    (cancel-tasks! tasks executor process)
    (swap!
     status
     (comp
      (partial activate-front! state now executor @commands)
      (partial expunge-keys data)
      (partial find-existing store now)))
    (log/debug! "EXPIRED" down)
    down))

(defn halt-flow!
  [{:keys [root flow tasks status store events] :as state} executor]
  (let [now @flow
        halting (flow/process-map now)]
    (cancel-tasks! tasks executor (keys halting))
    (swap!
     status
     (comp
      (partial find-existing store now)
      #(assoc % :state :halted)))
    (executor/declare-event!
     events
     {:event "flow-halted"
      :root root})))

(defn merge-processes!
  [{:keys [flow commands status tasks store] :as state} executor processes]
  (let [transform (command/transform-processes @commands processes)]
    (cancel-tasks! tasks executor (keys transform))
    (swap! flow #(flow/merge-processes % (vals transform)))
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
        (partial find-existing store now))))))

(defn expire-commands!
  [{:keys [flow commands] :as state} executor expiring]
  (let [processes (template/map-cat (partial flow/command-processes @flow) expiring)]
    (log/debug! "expiring processes" processes "from commands" (into [] expiring))
    (expire-keys! state executor processes)))

(defn merge-commands!
  [{:keys [flow commands status tasks store] :as state} executor merging]
  (log/debug! "COMMANDS" (keys merging))
  (swap! commands merge merging)
  (try
    (expire-commands! state executor (keys merging))
    (catch Exception e
      (log/exception! e "merge-commands"))))
