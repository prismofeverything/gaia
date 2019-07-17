(ns gaia.funnel-test
  (:require
   [clojure.test :refer :all]
   [gaia.command :as command]
   [gaia.store :as store]
   [gaia.funnel :as funnel]))

(def commands
  [{:key "echo"
    :image_name "alpine"
    :cmd ["cat" "/tmp/in"]
    :stdout "/tmp/out"}

   {:key "gdc-extract"
    :image_name "biostream/gdc-extract"
    :cmd ["/opt/gdc-scan.py" "cases" "list"
          "--id" "ceead734-1ce0-4385-b65a-a9c853b7308e"]
    :stdout "/tmp/out"}])

(def echo-hello-world
  {:key :echo-hello-world
   :command :echo
   :inputs {"/tmp/in" {:contents "hello world!"}}
   :outputs {"/tmp/out" "hellooooo"}})

(def gdc-extract-process
  {:key :gdc-extract
   :command :gdc-extract
   :inputs {}
   :outputs {"/tmp/out" "gdc-cases.json"}})

(defn funnel-task
  [store commands process]
  (funnel/funnel-task
   {:funnel {:zone "test"}}
   store
   commands
   process))

(deftest funnel-test
  (testing "running flows"
    (let [store (store/load-file-store {:root "test"} "test")
          task (funnel-task store (command/index-key commands) echo-hello-world)]
      (println "funnel task:" task))))
