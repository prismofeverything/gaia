(ns gaia.matrix
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]))

(defn load-triples
  [path]
  (string/split (slurp path) #"\n"))

;; example: Map(signature -> linearSignature:belinostat_median, level -> -3.9119318340443594, expression -> geneExpression:TCGA-BH-A0DI-01A-21R-A12P-07)

(def triple-schema
  {:sample #"expression -> geneExpression:([^,)]+)"
   :signature #"signature -> linearSignature:([^,)]+)"
   :level #"level -> ([^,)]+)"})

(defn parse-schema
  [schema raw]
  (into
   {}
   (map
    (fn [[k re]]
      (let [found (re-find re raw)]
        (if found
          [k (last found)])))
    schema)))

(defn parse-triple
  [s]
  (let [triple (parse-schema triple-schema s)]
    (try
      (update triple :level (fn [n] (Double/parseDouble n)))
      (catch Exception e nil))))

(defn gather-rows
  [triples row column data]
  (update
   (reduce
    (fn [point triple]
      (-> point
          (assoc-in [:rows (get triple row) (get triple column)] (get triple data))
          (update :header (fn [header] (conj header (name (get triple column)))))))
    {:header #{}} triples)
   :rows dissoc nil))

(defn export-row
  [header [key row]]
  (cons
   (name key)
   (map
    (fn [column]
      (get row column))
    header)))

(defn export-rows
  [{:keys [header rows]}]
  (let [ex (map (partial export-row header) rows)
        full (cons "sample" header)]
    (cons full ex)))

(defn write-rows
  [rows path]
  (with-open [w (io/writer path :append true)]
    (doseq [row rows]
      (.write w (str row "\n")))))

(defn export-tsv
  [rows path]
  (let [export (map (fn [row] (string/join "\t" row)) rows)]
    (write-rows export path)))

(defn convert-triples
  [in out]
  (let [raw (load-triples in)
        triples (filter identity (map parse-triple raw))
        rows (gather-rows triples :sample :signature :level)
        export (export-rows rows)]
    (export-tsv export out)))
