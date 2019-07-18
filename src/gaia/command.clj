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
  [{:keys [inputs outputs]} step]
  ;; (log/debug! "VALIDATE" (pp step))
  (let [pin (:inputs step)
        pout (:outputs step)
        pvars (:vars step)]
    (if-not (empty? (difference (map keyword inputs) (keys pin)))
      (throw (Exception. (str (:key step) " - all inputs must be specified: " inputs (keys pin))))
    (if-not (empty? (difference (map keyword outputs) (keys pout)))
      (throw (Exception. (str (:key step) " - all outputs must be specified: " outputs (keys pout))))))))

(defn substitute-values
  [template values]
  (into
   {}
   (map
    (fn [[k t]]
      [k (get values (keyword t))])
    template)))

(defn generate-binding
  [step step output global]
  (str
   "composite/"
   step "/"
   step "/"
   output ":"
   global))

(defn generate-outputs
  [step outputs step]
  (reduce
   (fn [all [key template]]
     (if (get-in step [:outputs (keyword template)])
       all
       (assoc all (keyword template) (generate-binding (:key step) (:key step) (name key) template))))
   outputs (:outputs step)))

(declare apply-composite)

(defn apply-step
  [commands step vars inputs outputs {:keys [key command] :as step}]
  (let [ovars (template/evaluate-map (:vars step) vars)
        oin (substitute-values (:inputs step) inputs)
        oout (substitute-values (:outputs step) outputs)
        inner {:key (str (:key step) ":" key)
               :command command
               :vars ovars
               :inputs oin
               :outputs oout}
        exec (get commands (keyword command))]
    (apply-composite commands exec inner)))

(defn apply-composite
  [commands {:keys [vars inputs outputs steps] :as command} step]
  (if steps
    (do
      (validate-apply-composite! command step)
      (let [generated (reduce (partial generate-outputs step) {} steps)
            apply-partial (partial
                           apply-step
                           commands
                           step
                           (:vars step)
                           (merge (:inputs step) (:outputs step) generated)
                           (merge (:outputs step) generated))
            asteps (mapcat apply-partial steps)]
        (mapv identity asteps)))
    [step]))

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
  [{:keys [key vars inputs outputs] :as step}]
  (let [arrays (filter-map (fn [k v] (coll? v)) vars)
        series (cartesian-map arrays)]
    (map
     (fn [arrayed]
       (let [order (sort-by first arrayed)
             values (map (comp clean-string last) order)
             unique (string/join "-" (conj values key))
             env (walk/stringify-keys (merge vars arrayed))]
         (merge
          step
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
  [steps]
  (template/map-cat template-vars steps))

(defn transform-steps
  [commands steps]
  (let [templates (fill-templates steps)]
    (index-key
     (template/map-cat
      (fn [step]
        (let [command (get commands (keyword (:command step)))]
          (apply-composite commands command step)))
      templates))))

