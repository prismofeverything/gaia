(ns gaia.config
  (:require
   [clojure.walk :as walk]
   [clojure.string :as string]
   [taoensso.timbre :as log]
   [yaml.core :as yaml]
   [protograph.template :as template]
   [ophion.config :as config]
   [gaia.command :as command]
   [gaia.store :as store]
   [gaia.swift :as swift]
   [gaia.cloud :as cloud]
   [gaia.sisyphus :as sisyphus]))

(def config-keys
  [:variables
   :commands
   :processes
   :agents])

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
              (catch Exception e (do (log/info "bad yaml" path key) [key {}]))))
          config-keys))
        config (update config :commands command/index-key)
        config (update config :processes (partial command/transform-processes (:commands config)))]
    config))

(defn load-processes
  [path]
  (let [processes (parse-yaml path)]
    (command/fill-templates processes)))

(defn load-commands
  [path]
  (command/index-key
   (parse-yaml
    (str path ".commands.yaml"))))

(defn load-config
  [path]
  (let [config (config/read-path path)
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
