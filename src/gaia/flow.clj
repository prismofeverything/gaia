(ns gaia.flow
  (:require
   [clojure.set :as set]
   [ubergraph.core :as graph]
   [ubergraph.alg :as alg]))

(defn map-cat
  [f s]
  (reduce into [] (map f s)))

(defn process-init
  [{:keys [key inputs outputs] :as process}]
  (let [node [key (assoc process :_process true)]
        incoming (map
                  (fn [input]
                    [input key])
                  (vals inputs))
        outgoing (map
                  (fn [output]
                    [key output])
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

(defn process-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (filter
     #(graph/attr flow % :_process)
     nodes)))

(defn process-map
  [flow]
  (let [nodes (process-nodes)]
    (into
     {}
     (map
      (fn [node]
        [node (graph/attrs flow node)])
      nodes))))

(defn data-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (remove
     #(graph/attr flow % :_process)
     nodes)))

(defn imminent-front
  [flow data]
  (let [processes (process-nodes flow)]
    (filter
     (fn [process]
       (and
        (every? data (graph/predecessors flow process))
        (not-every? data (graph/successors flow process))))
     processes)))

(defn query-nodes
  [flow p?]
  (filter
   (fn [node]
     (p? node (graph/attrs flow node)))
   (graph/nodes flow)))

(defn command-processes
  [flow command]
  (query-nodes
   flow
   (fn [key node]
     (= (:command node) command))))

(defn find-descendants
  [flow nodes]
  (set
   (map-cat
    (partial alg/pre-traverse flow)
    nodes)))
