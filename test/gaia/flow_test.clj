(ns gaia.flow-test
  (:require
   [clojure.test :refer :all]
   [sisyphus.log :as log]
   [gaia.flow :as flow]))

(def line-commands
  {:command
   {:line
    (fn [{:keys [m x b]}]
      {:z (+ (* m x) b)})

    :triangle
    (fn [{:keys [a b]}]
      {:c
       (Math/sqrt
        (+
         (* a a)
         (* b b)))})}})

(def line-processes
  [{:key :line-four
    :command :line
    :inputs {:m :one
             :x :two
             :b :two}
    :outputs {:z :four}}

   {:key :triangle-five
    :command :triangle
    :inputs {:a :three
             :b :four}
    :outputs {:c :five}}

   {:key :line-eleven
    :command :line
    :inputs {:m :two
             :x :three
             :b :five}
    :outputs {:z :eleven}}

   {:key :line-omega
    :command :line
    :inputs {:m :one
             :x :three
             :b :five}
    :outputs {:z :omega}}

   {:key :line-twenty-six
    :command :line
    :inputs {:m :three
             :x :five
             :b :eleven}
    :outputs {:z :twenty-six}}

   {:key :line-twelve
    :command :line
    :inputs {:m :two
             :x :five
             :b :two}
    :outputs {:z :twelve}}

   {:key :triangle-thirteen
    :command :triangle
    :inputs {:a :five
             :b :twelve}
    :outputs {:c :thirteen}}])

(def line-flow
  (reduce
   flow/add-node
   line-commands
   line-processes))

(def starting-data
  {:one 1 :two 2 :three 3})

;; (deftest flow-test
;;   (testing "running flows"
;;     (let [{:keys [data]} (flow/run-flow line-flow starting-data)
;;           next (flow/update-data line-flow data :three 11)
;;           alternate (flow/run-flow line-flow next)]
;;       (log/info! "flow" line-flow)
;;       (log/info! "data" data)
;;       (log/info! "next" next)
;;       (log/info! "alternate" (:data alternate))
;;       (is (= (:twenty-six data) 26.0))
;;       (is (= (:thirteen data) 13.0))
;;       (is (> (get-in alternate [:data :thirteen]) 25))
;;       (is (> (get-in alternate [:data :twenty-six]) 150)))))
