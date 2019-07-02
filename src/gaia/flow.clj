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
  (string/join
   ":"
   (rest
    (string/split bound #":"))))

(defn process-init
  [{:keys [key inputs outputs] :as process}]
  (let [process-key (prefix "process" key)
        node [key (assoc process :_process true)]
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
  [flow nodes]
  (into
   {}
   (map
    (fn [node]
      [(unfix node) (graph/attrs flow node)])
    nodes)))

(defn process-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (filter
     #(graph/attr flow % :_process)
     nodes)))

(defn process-map
  [flow]
  (node-map flow (process-nodes flow)))

(defn data-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (remove
     #(graph/attr flow % :_process)
     nodes)))

(defn data-map
  [flow]
  (node-map flow (data-nodes flow)))

(defn map-prefix
  [pre nodes]
  (map (partial prefix pre) nodes))

(defn imminent-front
  [flow data]
  (let [processes (process-nodes flow)
        prefix-data (map-prefix "data" data)]
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
