(ns gaia.flow
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [ubergraph.core :as graph]
   [ubergraph.alg :as alg]))

(defn map-cat
  [f s]
  (reduce into [] (map f s)))

(defn prefix
  [pre post]
  (str pre ":" post))

(defn unfix
  [bound]
  (let [colon (.indexOf bound ":")]
    (.substring bound (inc colon))))

(defn process-init
  [{:keys [key inputs outputs] :as process}]
  (let [process-key (prefix "process" key)
        node [process-key (assoc process :_process true)]
        incoming (map
                  (fn [input]
                    [(prefix "data" input) process-key])
                  (vals inputs))
        outgoing (map
                  (fn [output]
                    [process-key (prefix "data" output)])
                  (vals outputs))]
    (cons
     node
     (concat incoming outgoing))))

(defn generate-flow
  [processes]
  (let [init (map-cat process-init processes)]
    (apply graph/digraph init)))

(defn merge-processes
  [flow processes]
  (let [init (map-cat process-init processes)]
    (apply graph/build-graph flow init)))

(defn node-map
  [pre flow nodes]
  (into
   {}
   (map
    (fn [node]
      [node (graph/attrs flow (prefix pre node))])
    nodes)))

(defn process-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (filter
     #(graph/attr flow % :_process)
     nodes)))

(defn process-map
  ([flow]
   (process-map flow (map unfix (process-nodes flow))))
  ([flow nodes]
   (node-map "process" flow nodes)))

(defn data-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (remove
     #(graph/attr flow % :_process)
     nodes)))

(defn data-map
  ([flow]
   (data-map flow (map unfix (data-nodes flow))))
  ([flow nodes]
   (node-map "data" flow nodes)))

(defn map-prefix
  [pre nodes]
  (map (partial prefix pre) nodes))

(defn full-node
  [flow node]
  {:node node
   :incoming (graph/predecessors flow node)
   :outgoing (graph/successors flow node)})

(defn missing-data
  [flow data]
  (let [processes (process-nodes flow)
        full (map (partial full-node flow) processes)
        prefix-data (set (map-prefix "data" data))
        incomplete
        (filter
         (fn [{:keys [node incoming outgoing]}]
           (and
            (not-every? prefix-data incoming)
            (not-every? prefix-data outgoing)))
         full)
        inputs (set (mapcat :incoming incomplete))]
    (mapv unfix (set/difference inputs prefix-data))))

(defn imminent-front
  [flow data]
  (let [processes (process-nodes flow)
        prefix-data (set (map-prefix "data" data))]
    (map
     unfix
     (filter
      (fn [process]
        (and
         (every? prefix-data (graph/predecessors flow process))
         (not-every? prefix-data (graph/successors flow process))))
      processes))))

(defn query-nodes
  [flow p?]
  (map
   unfix
   (filter
    (fn [node]
      (p? node (graph/attrs flow node)))
    (graph/nodes flow))))

(defn command-processes
  [flow command]
  (query-nodes
   flow
   (fn [key node]
     (= (:command node) command))))

(defn find-descendants
  [flow nodes]
  (let [process (map-prefix "process" nodes)
        data (map-prefix "data" nodes)
        parallel (concat process data)]
    (set
     (map
      unfix
      (map-cat
       (partial alg/pre-traverse flow)
       parallel)))))
