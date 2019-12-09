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

(defn log-debug-if!
  "Log a debug message if the sequence is not empty."
  [message if-seq]
  (when (seq if-seq)
    (log/debug! message if-seq)))

(defn log-info-if!
  "Log an info message if the sequence is not empty."
  [message if-seq]
  (when (seq if-seq)
    (log/info! message if-seq)))

(defn- guess-owner
  "Guess a default :owner name from a workflow name like
  owner_type_timestamp or owner_type_timestamp__description."
  [workflow]
  (first (string/split (name workflow) #"_" 2)))

(defn- guess-properties
  "Guess default properties from a workflow name."
  ; Also extract :description from owner_type_timestamp__description?
  [workflow]
  {:owner (guess-owner workflow)})

(defn generate-sync
  "Construct a new workflow."
  [workflow kafka store]
  (let [flow (flow/generate-flow [])]
    {:workflow workflow
     :flow (atom flow)
     :commands (atom {})
     :properties (atom (guess-properties workflow))
     :store store
     :events (:producer kafka)
     :status
     (atom
      {:state :initialized
       :data {}
       :tasks {}})}))

(defn summarize-flow
  "Return summary info on a workflow for the 'workflows' endpoint.
  TODO(jerry): Include counts of waiting/ready/running/completed steps."
  [flow]
  (let [{:keys [state]} @(:status flow)
        properties @(:properties flow)]
    {:name (name (:workflow flow))
     :state state
     :properties properties
     :step-count (count (flow/step-nodes @(:flow flow)))}))

(def running-states
  #{:running :error :exception})

(defn find-running
  [tasks]
  (into
   {}
   (filter
    (fn [[key task]]
      (running-states (:state task)))
    tasks)))

(defn send-tasks!
  [executor workflow commands prior tasks]
  (log-debug-if! "prior" prior)
  (log-debug-if! "tasks" tasks)
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
    (log-debug-if! "running" (mapv first running))
    (log-debug-if! "triggered" (mapv first triggered))
    (merge triggered prior)))

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
  [{:keys [workflow store] :as state} flow executor commands status]
  (let [complete (complete-keys (:data status))
        front (mapv identity (flow/imminent-front flow complete))]
    (log-debug-if! "complete keys" (sort complete))
    (log-debug-if! "front" front)
    (log-debug-if! "waiting for" (flow/missing-data flow complete))
    (if (empty? front)
      (let [missing (missing-data flow (:data status))]
        (log/debug! "empty front - missing" missing)
        (assoc status :state (if (empty? missing) :complete :incomplete)))
      (let [launching (flow/step-map flow front)]
        (log/debug! "launching" launching)
        (assoc
         status
         :state :running
         :tasks (send-tasks! executor workflow commands (:tasks status) launching))))))

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
  [{:keys [status]} event state]
  (swap!
   status
   update
   :tasks
   (fn [tasks event]
     (if-let [found (find-task tasks (:id event))]
       (do
         (log/debug! "found" found)
         (-> tasks
             (assoc-in [(first found) :state] state)
             (assoc-in [(first found) :event] event)))
       tasks))
   event))

(defn data-complete!
  [{:keys [workflow flow commands status events] :as state} executor event]
  (if (= :halted (:state @status))
    (swap! status update :data dissoc (:key event))
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
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

        (log/debug! "workflow continues" (name workflow))))))

(defn executor-events!
  [{:keys [workflow status flow] :as state}
   executor topic event]
  (log/debug! "worker event" (:event event) "for" (name workflow) event)
  (condp = (:event event)

    "step-start"
    ()

    "step-complete"
    (step-state! state event :complete)

    "step-error"
    (step-state! state event :error)

    "task-error"
    (step-state! state event :exception)

    "data-complete"
    (data-complete! state executor event)

    "step-terminated"
    ()

    "container-create"
    ()

    "execution-start"
    ()

    "execution-complete"
    ()

    "container-exit"
    ()

    (log/warn! "unknown worker event" (:event event))))

(defn initial-key
  [key]
  [key {:state :complete}])

(defn target-tasks
  [tasks outstanding]
  (let [potential (select-keys tasks outstanding)]
    (find-running potential)))

(defn dissoc-seq
  [m s]
  (apply dissoc m s))

(defn cancel-tasks!
  [status executor canceling]
  ; TODO(jerry): Prune to existing tasks before logging.
  ; TODO(jerry): Does `canceling` contain strings or keywords?
  (let [found (select-keys (:tasks @status) canceling)]
    (doseq [[key task] found]
      (when (= (:state task) :running)
        (log-info-if! "cancelling" key)
        (executor/cancel! executor (:id task))))
    (swap!
     status
     update
     :tasks
     dissoc-seq
     (keys found))))

(defn find-existing
  [store flow status]
  (let [data (flow/data-nodes flow)]
    ; (log/debug! "data" data)  ; "clojure.lang.LazySeq@a96b6ad4"
    (if (empty? data)
      status
      (let [[complete missing] (store/partition-data store data)
            existing (into {} (map initial-key complete))]
        (log/debug! "existing" existing)
        (log/debug! "absent" (set/difference (set data) (set (keys existing))))
        (assoc status :data existing)))))

(defn run-flow!
  [{:keys [workflow flow commands store status] :as state} executor]
  (when (not= :running (:state @status))
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
        (partial find-existing store now))))))

(defn expunge-keys
  [descendants status]
  (update status :data dissoc-seq descendants))

(defn expire-keys!
  [{:keys [flow commands store status] :as state} executor expiring]
  (let [now (deref flow)
        {:keys [data step] :as down} (flow/find-descendants now expiring)
        tasks (:tasks @status)
        targets (target-tasks tasks step)]
    (log-debug-if! "descendants" down)
    (cancel-tasks! status executor step)
    (log-debug-if! "tasks" tasks)
    (swap!
     status
     (comp
      (partial activate-front! state now executor @commands)
      (partial expunge-keys data)
      (partial find-existing store now)))
    (log-debug-if! "expired" down)
    down))

(defn halt-flow!
  [{:keys [workflow flow status store events] :as state} executor]
  (let [now @flow
        halting (flow/step-map now)]
    (cancel-tasks! status executor (keys halting))
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

(defn merge-properties!
  "Merge the given properties into the workflow, computing some defaults."
  [{:keys [properties workflow] :as state} new-properties]
  (let [defaults (guess-properties workflow)
        new-properties (merge defaults new-properties)]
    (log-debug-if! "merging properties" (keys new-properties))
    (swap! properties merge new-properties)))

(defn merge-steps!
  [{:keys [flow commands status store] :as state} executor steps]
  (log/debug! "transforming" (count steps) "steps")
  (let [transform (command/transform-steps @commands steps)]
    (log/debug! "cancelling replaced tasks")
    (cancel-tasks! status executor (keys transform))
    (log/debug! "merging" (count transform) "steps")
    (swap! flow #(flow/merge-steps % (vals transform)))
    (log/debug! "looking for existing data")
    (let [now @flow]
      (swap!
       status
       (comp
        (partial activate-front! state now executor @commands)
        (partial find-existing store now))))))

(defn expire-commands!
  [{:keys [flow] :as state} executor expiring]
  (let [steps (template/map-cat (partial flow/command-steps @flow) expiring)]
    (when (seq steps)
      (log/debug! "expiring steps" steps "from commands" (into [] expiring)))
    (expire-keys! state executor steps)))

(defn merge-commands!
  [{:keys [commands] :as state} executor merging]
  (let [new-keys (keys merging)]
    (log-debug-if! "merging commands" new-keys)
    (swap! commands merge merging)
    (try
      (expire-commands! state executor new-keys)
      (catch Exception e
        (log/exception! e "merge-commands")))))
