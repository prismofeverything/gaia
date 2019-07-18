(ns gaia.config
  (:require
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [clojure.string :as string]
   [yaml.core :as yaml]
   [protograph.template :as template]
   [sisyphus.log :as log]
   [gaia.command :as command]
   [gaia.store :as store]
   [gaia.swift :as swift]
   [gaia.cloud :as cloud]
   [gaia.sisyphus :as sisyphus]))

(def config-keys
  [:variables
   :commands
   :steps
   :agents])

(defn read-path
  [path]
  (edn/read-string
   (slurp path)))

(defn parse-yaml
  [path]
  (yaml/parse-string (slurp path)))

(defn load-flow-config
  [path]
  (let [config
        (into
         {}
         (map
          (fn [key]
            (try
              [key (parse-yaml (str path "." (name key) ".yaml"))]
              (catch Exception e
                (do (log/exception! e "bad yaml" path key)
                  [key {}]))))
          config-keys))
        config (update config :commands (partial command/index-key :name))
        config (update config :steps (partial command/transform-steps (:commands config)))]
    config))

(defn load-steps
  [path]
  (let [steps (parse-yaml path)]
    (command/fill-templates steps)))

(defn load-commands
  [path]
  (command/index-key
   :name
   (parse-yaml
    (str path ".commands.yaml"))))

(defn load-config
  [path]
  (let [config (read-path path)
        commands (load-commands (get-in config [:flow :path]))]
    (assoc config :commands commands)))

(defn load-store
  [config]
  (condp = (keyword (:type config))
    :file (store/file-store-generator config)
    :swift (swift/swift-store-generator config)
    :cloud (cloud/cloud-store-generator config)
    (store/file-store-generator config)))

(defn load-executor
  [config]
  (condp = (keyword (:target config))
    :sisyphus (sisyphus/load-sisyphus-executor config)))
