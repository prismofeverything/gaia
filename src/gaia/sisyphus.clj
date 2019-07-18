(ns gaia.sisyphus
  (:require
   [sisyphus.kafka :as kafka]
   [sisyphus.rabbit :as rabbit]
   [gaia.executor :as executor]))

(defn generate-id
  []
  (.toString
   (java.util.UUID/randomUUID)))

(defn find-xput
  [step command x key]
  [(get-in step [x key])
   (get-in command [x key])])

(defn find-xputs
  [step command x]
  (map
   (partial find-xput step command x)
   (keys (get command x))))

(defn step->task
  [step command]
  {:id (or (:id step) (generate-id))
   :name (:name step)
   :workflow (:workflow step "gaia")
   :image (:image command)
   :command (:command command)
   :inputs (find-xputs step command :inputs)
   :outputs (find-xputs step command :outputs)})

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
