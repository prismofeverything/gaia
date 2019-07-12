(ns gaia.sisyphus
  (:require
   [sisyphus.kafka :as kafka]
   [sisyphus.rabbit :as rabbit]
   [gaia.executor :as executor]))

(defn generate-id
  []
  (java.util.UUID/randomUUID))

(defn find-xput
  [process command x key]
  [(get-in process [x key])
   (get-in command [x key])])

(defn find-xputs
  [process command x]
  (map
   (partial find-xput process command x)
   (keys (get command x))))

(defn process->task
  [process command]
  {:id (or (:id process) (generate-id))
   :root (:root process "gaia")
   :image (:image command)
   :commands (:commands command)
   :inputs (find-xputs process command :inputs)
   :outputs (find-xputs process command :outputs)})

(defn submit-task!
  [{:keys [rabbit]} commands process]
  (let [command (get commands (keyword (:command process)))
        task (process->task process command)]
    (rabbit/publish! rabbit task)
    task))

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
    [executor commands process]
    (submit-task! sisyphus commands process))
  (cancel!
    [executor id]
    (cancel-task! sisyphus id)))

(defn load-sisyphus-executor
  "required keys are :rabbit and :kafka"
  [config]
  (SisyphusExecutor. config))
