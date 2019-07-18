(ns gaia.network
  (:require
   [clojure.set :as set]
   [ubergraph.core :as graph]))

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

(defn step-init
  [{:keys [key inputs outputs] :as step}]
  (let [node [key (assoc step :_step true)]
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
  [steps]
  (let [init (map-cat step-init steps)]
    (apply graph/digraph init)))

(defn merge-steps
  [flow steps]
  (let [init (map-cat step-init steps)]
    (apply graph/build-graph flow init)))

(defn step-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (filter
     #(graph/attr flow % :_step)
     nodes)))

(defn data-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (remove
     #(graph/attr flow % :_step)
     nodes)))

(defn immanent-front
  [flow data]
  (let [steps (step-nodes flow)]
    (filter
     (fn [step]
       (and
        (every? data (graph/predecessors flow step))
        (not-every? data (graph/successors flow step))))
     steps)))
