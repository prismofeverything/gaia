(ns gaia.flow
  (:require
   [clojure.set :as set]
   [ubergraph.core :as graph]
   [ubergraph.alg :as alg]))

(defn map-cat
  [f s]
  (reduce into [] (map f s)))

(defn neighbors
  [graph node]
  (set/union
   (set (graph/predecessors graph node))
   (set (graph/successors graph node))))

(defn bipartite-color
  "Attempts a two-coloring of graph g. When successful, returns a map of
  nodes to colors (1 or 0). Otherwise, returns nil."
  [g]
  (letfn [(color-component [coloring start]
            (loop [coloring (assoc coloring start 1)
                   queue (conj clojure.lang.PersistentQueue/EMPTY start)]
              (if (empty? queue)
                coloring
                (let [v (peek queue)
                      color (- 1 (coloring v))
                      nbrs (neighbors g v)]
                  (if (some #(and (coloring %) (= (coloring v) (coloring %)))
                            nbrs)
                    (let [nbrs (remove coloring nbrs)]
                      (recur (into coloring (for [nbr nbrs] [nbr color]))
                             (into (pop queue) nbrs))))))))]
    (loop [[node & nodes] (seq (graph/nodes g))
           coloring {}]
      (when coloring
        (if (nil? node)
          coloring
          (if (coloring node)
            (recur nodes coloring)
            (recur nodes (color-component coloring node))))))))

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

(defn immanent-front
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
