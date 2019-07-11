(ns gaia.sync
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [taoensso.timbre :as log]
   [cheshire.core :as json]
   [protograph.template :as template]
   [sisyphus.kafka :as kafka]
   [gaia.flow :as flow]
   [gaia.command :as command]
   [gaia.store :as store]
   [gaia.executor :as executor]))

(defn generate-sync
  [kafka root processes store]
  (let [flow (flow/generate-flow (vals processes))]
    {:root root
     :flow (atom flow)
     :store store
     :events (:producer kafka)
     :status
     (atom
      {:state :initialized
       :data {}})
     :tasks (agent {})}))

(defn send-tasks!
  [executor store commands prior tasks]
  (log/info "PRIOR" prior)
  (log/info "TASKS" tasks)
  (let [relevant (remove (comp (partial get prior) first) tasks)
        submit! (partial executor/submit! executor commands)
        triggered (into
                   {}
                   (map
                    (fn [[key task]]
                      [key (submit! task)])
                    relevant))]
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
  [{:keys [store tasks] :as state} flow executor commands status]
  (let [complete (complete-keys (:data status))
        front (mapv identity (flow/imminent-front flow complete))]
    (log/info "COMPLETE KEYS" (sort complete))
    (log/info "FRONT" front)
    (log/info "WAITING FOR" (flow/missing-data flow complete))
    (if (empty? front)
      (let [missing (missing-data flow (:data status))]
        (log/info "empty front - missing" missing)
        (if (empty? missing)
          (assoc status :state :complete)
          (assoc status :state :incomplete)))
      (let [active (flow/process-map flow front)
            current @tasks
            launching (apply dissoc active (keys current))
            computing (apply merge (map compute-outputs (vals launching)))]
        (log/info "ACTIVE" active)
        (log/info "LAUNCHING" launching)
        (send tasks (partial send-tasks! executor store commands) launching)
        (-> status
            (update :data merge computing)
            (assoc :state :running))))))

(defn complete-key
  [event status]
  (assoc-in
   status [:data (:key event)]
   (merge
    (select-keys event [:root :path :key])
    {:url (:key event)
     :state :complete})))

(defn process-state!
  [{:keys [tasks]} event]
  (send
   tasks
   (fn [ts {:keys [id state]}]
     (if-let [found (first (filter #(= id (:id (last %))) ts))]
       (let [[key task] found]
         (assoc-in ts [key :state] (keyword (string/lower-case state))))))
   event))

(defn data-complete!
  [{:keys [flow status events] :as state} executor commands root event]
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

        (log/info "FLOW CONTINUES" root)))))

(defn executor-events!
  [{:keys [status] :as state}
   executor commands root topic event]
  (log/info "GAIA EVENT" event)
  (condp = (:event event)

    "process-state"
    (process-state! state event)

    "data-complete"
    (when (= (:root event) (name root))
      (data-complete! state executor commands root event))

    (log/info "other executor event" event)))

;; (defn find-existing
;;   [store root status]
;;   (let [existing (store/existing-paths store root)]
;;     (assoc status :data existing)))

(defn initial-key
  [key]
  [key {:state :complete}])

(defn find-existing
  [store flow status]
  (let [data (flow/data-nodes flow)
        [complete missing] (store/partition-data store data)
        existing (into {} (map initial-key complete))]
    (assoc status :data existing)))

(defn events-listener!
  [state executor commands root kafka]
  (let [status-topic (get kafka :status-topic "gaia-status")
        topics ["gaia-events" status-topic]
        kafka (update kafka :subscribe concat topics)
        handle (partial executor-events! state executor commands root)]
    (kafka/boot-consumer kafka handle)))

(defn running-task?
  [{:keys [state] :as task}]
  (or
   (= :initializing state)
   (= :running state)))

(defn executor-cancel!
  [executor tasks outstanding]
  (let [potential (select-keys tasks outstanding)
        canceling (filter running-task? (vals potential))
        expunge (mapv :name canceling)]
    (log/info "canceling tasks" expunge)
    (doseq [cancel canceling]
      (executor/cancel! executor (:id cancel)))
    (apply dissoc tasks expunge)))

(defn cancel-tasks!
  [tasks executor canceling]
  (send tasks (partial executor-cancel! executor) canceling))

(defn initialize-flow!
  [root store executor kafka commands]
  (let [flow (generate-sync kafka root [] store)
        listener (events-listener! flow executor commands root kafka)]
    flow))

(defn trigger-flow!
  [{:keys [flow store status tasks] :as state} root executor commands]
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

;; TODO(ryan): get this to work with the new flow/find-descendants
(defn expire-keys!
  [{:keys [flow status tasks] :as state} executor commands expiring]
  (let [now (deref flow)
        {:keys [data process] :as down} (flow/find-descendants now expiring)]
    (send tasks dissoc-seq process)
    (swap!
     status
     (comp
      (partial activate-front! state now executor @commands)
      (partial expunge-keys data)))
    (log/info "expired" down)
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
  [{:keys [flow status tasks store] :as state} executor commands processes]
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
  [{:keys [flow] :as state} executor commands expiring]
  (let [processes (template/map-cat (partial flow/command-processes @flow) expiring)]
    (log/info "expiring processes" processes)
    (log/info "from commands" (into [] expiring))
    (expire-keys! state executor commands processes)))
