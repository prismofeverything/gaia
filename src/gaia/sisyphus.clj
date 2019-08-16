(ns gaia.sisyphus
  (:require
   [clojure.walk :as walk]
   [protograph.template :as template]
   [sisyphus.kafka :as kafka]
   [sisyphus.rabbit :as rabbit]
   [gaia.executor :as executor]))

(defn generate-id
  []
  (.toString
   (java.util.UUID/randomUUID)))

(defn find-xput
  [step command vars x key]
  (let [path (get-in command [x key])]
    [(get-in step [x key])
     (template/evaluate-template path vars)]))

(defn find-xputs
  [step command vars x]
  (map
   (partial find-xput step command vars x)
   (keys (get command x))))

(defn step->task
  [step command]
  (let [vars (walk/stringify-keys (merge (:vars command) (:vars step)))
        evaluated (walk/stringify-keys (template/evaluate-map vars (merge template/defaults vars)))
        all-vars (merge template/defaults evaluated)
        inputs (find-xputs step command all-vars :inputs)
        outputs (find-xputs step command all-vars :outputs)]
    {:id (or (:id step) (generate-id))
     :name (:name step)
     :workflow (:workflow step "gaia")
     :image (:image command)
     :command (map #(template/evaluate-template % all-vars) (:command command))
     :inputs inputs
     :outputs outputs}))

(defn submit-task!
  [{:keys [rabbit]} commands step]
  (let [command (get commands (keyword (:command step)))
        task (step->task step command)]
    (rabbit/publish! rabbit task)
    (assoc task :state :running)))

(defn cancel-task!
  [{:keys [kafka]} id]
  (kafka/send!
   (:producer kafka)
   (get-in kafka [:config :control-topic] "gaia-control")
   {:event "terminate"
    :id id}))

(deftype SisyphusExecutor [sisyphus]
  executor/Executor
  (submit!
    [executor commands task]
    (submit-task! sisyphus commands task))
  (cancel!
    [executor id]
    (cancel-task! sisyphus id)))

(defn load-sisyphus-executor
  "required keys are :rabbit and :kafka"
  [config]
  (SisyphusExecutor. config))
