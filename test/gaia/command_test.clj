(ns gaia.command-test
  (:require
   [clojure.test :refer :all]
   [sisyphus.log :as log]
   [gaia.config :as config]
   [gaia.flow :as flow]
   [gaia.command :as command]))

(def composite-data
  (config/load-flow-config "resources/test/test"))

(defn pp
  [clj]
  (with-out-str (clojure.pprint/pprint clj)))

(deftest inner-test
  (testing "inner composite process generation"
    (log/info! "commands" (pp (:commands composite-data)))
    (log/info! "processes" (pp (:processes composite-data)))
    (let [inner (get-in composite-data [:processes :inner-demo-invoke-TCGA-BRCA:wc-step])]
      (is inner)
      (is (= #{:YELLOW :OUTER} (set (keys (:inputs inner))))))))

