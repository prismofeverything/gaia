(ns gaia.oldflow
  (:require
   [clojure.set :as set]
   [taoensso.timbre :as log]))

(defn in? 
  [coll el]
  (some #(= el %) coll))

(defn set-conj
  [coll el]
  (conj (or coll (set nil)) el))

(defn setcat
  [seqs]
  (reduce into #{} seqs))

(defn data->process
  [flow data process]
  (-> flow
      (update-in [:data data :to] set-conj process)
      (update-in [:process process :from] set-conj data)))

(defn decipher-input
  [flow data process]
  (if (map? data)
    (cond
      (:content data) flow
      (:file data) (data->process flow (:file data) process)
      :else flow)
    (data->process flow data process)))

(defn external-input
  [input]
  (cond
    (map? input) (:file input)
    :else input))

(defn external-inputs
  [inputs]
  (filter identity (map external-input inputs)))

(defn process->data
  [flow process data]
  (-> flow
      (update-in [:process process :to] set-conj data)
      (update-in [:data data :from] set-conj process)))

(defn add-node
  [flow
   {:keys [key inputs command outputs]
    :as node}]
  (let [process (reduce
                 (fn [flow data]
                   (decipher-input flow data key))
                 flow (vals inputs))
        data (reduce
              (fn [flow data]
                (process->data flow key data))
              process (vals outputs))
        full (assoc-in data [:process key :node] node)]
    full))

(defn add-nodes
  [flow nodes]
  (reduce add-node flow nodes))

(defn find-process
  [flow key]
  (get-in flow [:process (name key) :node]))

(defn process-keys
  [flow]
  (-> flow :process keys))

(defn process-map
  [flow keys]
  (reduce
   (fn [m key]
     (assoc m key (find-process flow key)))
   {} keys))

(defn flow-space
  [flow]
  (set
   (keys
    (:data flow))))

(defn complete-keys
  [data]
  (set
   (map
    first
    (filter
     (fn [[k v]]
       (= :complete (keyword (:state v))))
     data))))

(defn missing-data
  [flow data]
  (let [complete (complete-keys data)
        space (flow-space flow)]
    (log/info "complete" complete)
    (log/info "space" space)
    (set/difference space complete)))

(defn flow-complete?
  [flow data]
  (empty? (missing-data flow data)))

(defn runnable?
  [flow data process]
  (let [inputs (-> flow :process (get process) :node :inputs vals)
        external (external-inputs inputs)
        complete (complete-keys data)]
    (empty? (set/difference (set external) complete))))

(defn able-processes
  [flow data]
  (filter (partial runnable? flow data) (-> flow :process keys)))

(defn process-produces?
  [flow missing process]
  (let [out (-> flow :process (get process) :node :outputs vals)]
    (not (empty? (set/intersection missing (set out))))))

(defn find-candidates
  [flow data]
  (let [missing (missing-data flow data)
        able (able-processes flow data)]
    (log/info "missing" missing)
    (log/info "able" (mapv identity able))
    (filter
     (partial process-produces? flow missing)
     able)))

(defn pull-data
  [data inputs]
  (into
   {}
   (map
    (fn [[arg key]]
      [arg (get data key)])
    inputs)))

(defn stuff-data
  [data outputs]
  (into
   {}
   (map
    (fn [[out key]]
      {key (get data out)})
    outputs)))

(defn run-process
  [flow data process]
  (log/trace "run" process)
  (let [{:keys [inputs outputs command]} (get-in flow [:flow process :node])
        run (get-in flow [:command command])
        args (pull-data data inputs)
        result (run args)
        out (stuff-data result outputs)]
    (log/trace "command" command)
    (log/trace "inputs" inputs)
    (log/trace "args" args)
    (log/trace "outputs" outputs)
    (log/trace "result" result)
    (log/trace "out" out)
    out))

(defn step-flow
  [flow data]
  (let [candidates (find-candidates flow data)
        outcome (mapv (partial run-process flow data) candidates)]
    (log/trace "candidates" (mapv identity candidates))
    (reduce merge data outcome)))

(defn run-flow
  [flow data]
  (loop [data data]
    (if (flow-complete? flow data)
      {:data data :complete? true}
      (let [next (step-flow flow data)]
        (if (= next data)
          {:data data :complete? false}
          (recur next))))))

(defn data-descendants
  [flow from down]
  (loop [covered #{}
         data (set down)
         process (set from)]
    (if (empty? data)
      {:data covered :process process}
      (let [after (setcat (map (fn [key] (get-in flow [:data key :to])) data))
            to (setcat (map (fn [p] (get-in flow [:process p :to])) after))]
        (recur (set/union covered data) to (set/union process after))))))

(defn find-descendants
  [flow source]
  (log/info "FLOW" flow)
  (log/info "SOURCE" source)
  (let [processes (select-keys (:process flow) source)
        from (keys processes)
        to (apply set/union (map :to (vals processes)))
        data (select-keys (:data flow) source)
        down (concat to (keys data))]
    (log/info from to down)
    (data-descendants flow from down)))

(defn command-processes
  [{:keys [process]} command]
  (map
   first
   (filter
    (fn [[key {:keys [node]}]]
      (= command (:command node)))
    process)))

(defn expire-data
  [flow data key]
  (let [expiring (find-descendants flow key)]
    (apply dissoc data expiring)))

(defn update-data
  [flow data key value]
  (let [expired (expire-data flow data key)]
    (assoc expired key value)))

(defn generate-flow
  [processes]
  (add-nodes
   {}
   processes))
