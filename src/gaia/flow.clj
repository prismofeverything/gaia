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

(defn splitfix
  [bound]
  (let [colon (.indexOf bound ":")]
    [(keyword (.substring bound 0 colon))
     (.substring bound (inc colon))]))

(defn step-init
  [{:keys [name inputs outputs] :as step}]
  (let [step-name (prefix "step" name)
        node [step-name (assoc step :_step true)]
        incoming (map
                  (fn [input]
                    [(prefix "data" input) step-name])
                  (vals inputs))
        outgoing (map
                  (fn [output]
                    [step-name (prefix "data" output)])
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

(defn node-map
  [pre flow nodes]
  (into
   {}
   (map
    (fn [node]
      [node (graph/attrs flow (prefix pre node))])
    nodes)))

(defn step-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (map
     unfix
     (filter
      #(graph/attr flow % :_step)
      nodes))))

(defn step-map
  ([flow]
   (step-map flow (step-nodes flow)))
  ([flow nodes]
   (node-map "step" flow nodes)))

(defn data-nodes
  [flow]
  (let [nodes (graph/nodes flow)]
    (map
     unfix
     (remove
      #(graph/attr flow % :_step)
      nodes))))

(defn data-map
  ([flow]
   (data-map flow (data-nodes flow)))
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
  (let [steps (map-prefix "step" (step-nodes flow))
        full (map (partial full-node flow) steps)
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
  (let [steps (map-prefix "step" (step-nodes flow))
        prefix-data (set (map-prefix "data" data))]
    (map
     unfix
     (filter
      (fn [step]
        (and
         (every? prefix-data (graph/predecessors flow step))
         (not-every? prefix-data (graph/successors flow step))))
      steps))))

(defn query-nodes
  [flow p?]
  (map
   unfix
   (filter
    (fn [node]
      (p? node (graph/attrs flow node)))
    (graph/nodes flow))))

(defn command-steps
  [flow command]
  (query-nodes
   flow
   (fn [name node]
     (= (:command node) command))))

(defn find-descendants
  [flow nodes]
  (let [step (map-prefix "step" nodes)
        data (map-prefix "data" nodes)
        parallel (concat step data)
        downstream (map-cat (partial alg/pre-traverse flow) parallel)
        split (map splitfix downstream)]
    (reduce
     (fn [stream [what name]]
       (update stream what conj name))
     {} split)))
