(ns gaia.command
  (:require
   [clojure.set :as set]
   [clojure.walk :as walk]
   [clojure.string :as string]
   [clojure.math.combinatorics :as combinatorics]
   [sisyphus.log :as log]
   [protograph.template :as template]))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn difference
  [a b]
  (set/difference (set a) (set b)))

(defn pp
  [clj]
  (with-out-str (clojure.pprint/pprint clj)))

(defn validate-apply-composite!
  [{:keys [inputs outputs]} process]
  ;; (log/debug! "VALIDATE" (pp process))
  (let [pin (:inputs process)
        pout (:outputs process)
        pvars (:vars process)]
    (if-not (empty? (difference (map keyword inputs) (keys pin)))
      (throw (Exception. (str (:key process) " - all inputs must be specified: " inputs (keys pin))))
    (if-not (empty? (difference (map keyword outputs) (keys pout)))
      (throw (Exception. (str (:key process) " - all outputs must be specified: " outputs (keys pout))))))))

(defn substitute-values
  [template values]
  (into
   {}
   (map
    (fn [[k t]]
      [k (get values (keyword t))])
    template)))

(defn generate-binding
  [process step output global]
  (str
   "composite/"
   process "/"
   step "/"
   output ":"
   global))

(defn generate-outputs
  [process outputs step]
  (reduce
   (fn [all [key template]]
     (if (get-in process [:outputs (keyword template)])
       all
       (assoc all (keyword template) (generate-binding (:key process) (:key step) (name key) template))))
   outputs (:outputs step)))

(declare apply-composite)

(defn apply-step
  [commands process vars inputs outputs {:keys [key command] :as step}]
  (let [ovars (template/evaluate-map (:vars step) vars)
        oin (substitute-values (:inputs step) inputs)
        oout (substitute-values (:outputs step) outputs)
        inner {:key (str (:key process) ":" key)
               :command command
               :vars ovars
               :inputs oin
               :outputs oout}
        exec (get commands (keyword command))]
    (apply-composite commands exec inner)))

(defn apply-composite
  [commands {:keys [vars inputs outputs steps] :as command} process]
  (if steps
    (do
      (validate-apply-composite! command process)
      (let [generated (reduce (partial generate-outputs process) {} steps)
            apply-partial (partial
                           apply-step
                           commands
                           process
                           (:vars process)
                           (merge (:inputs process) (:outputs process) generated)
                           (merge (:outputs process) generated))
            asteps (mapcat apply-partial steps)]
        (mapv identity asteps)))
    [process]))

(defn index-seq
  [f s]
  (into
   {}
   (map (juxt f identity) s)))

(defn filter-map
  [f m]
  (into
   {}
   (filter
    (fn [[k v]]
      (f k v))
    m)))

(defn cartesian-map
  [map-of-seqs]
  (let [order (mapv vec map-of-seqs)
        heading (map first order)
        cartes (apply combinatorics/cartesian-product (map last order))]
    (map
     (fn [product]
       (into {} (map vector heading product)))
     cartes)))

(defn clean-string
  [s]
  (string/replace s #"[^a-zA-Z0-9\-]" ""))

(defn template-vars
  [{:keys [key vars inputs outputs] :as process}]
  (let [arrays (filter-map (fn [k v] (coll? v)) vars)
        series (cartesian-map arrays)]
    (map
     (fn [arrayed]
       (let [order (sort-by first arrayed)
             values (map (comp clean-string last) order)
             unique (string/join "-" (conj values key))
             env (walk/stringify-keys (merge vars arrayed))]
         (merge
          process
          {:key unique
           :vars env
           :inputs (template/evaluate-map inputs env)
           :outputs (template/evaluate-map outputs env)})))
     series)))

(defn index-key
  [s]
  (index-seq
   (comp keyword :key)
   s))

(defn fill-templates
  [processes]
  (template/map-cat template-vars processes))

(defn transform-processes
  [commands processes]
  (let [templates (fill-templates processes)]
    (index-key
     (template/map-cat
      (fn [process]
        (let [command (get commands (keyword (:command process)))]
          (apply-composite commands command process)))
      templates))))

