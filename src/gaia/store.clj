(ns gaia.store
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [protograph.kafka :as kafka]))

(defn join-path
  [elements]
  (.getPath (apply io/file elements)))

(defn snip
  [s prefix]
  (if (.startsWith s prefix)
    (.substring s (.length prefix))
    s))

(defn file->key
  [root file]
  (let [path (.getAbsolutePath file)]
    (snip path root)))

(defn dir-for
  [path]
  (.substring
   path 0
   (.lastIndexOf path java.io.File/separator)))

(defn ensure-path
  [path]
  (let [dir (io/file (dir-for path))]
    (.mkdirs dir)))

(defprotocol Store
  (present? [store key])
  (protocol [store])
  (partition-data [store data])
  (existing-keys [store path]))

(defprotocol Bus
  (put [bus topic message])
  (listen [bus topic fn]))

(defprotocol Executor
  (execute [executor key inputs outputs command])
  (status [executor task-id]))

(deftype FileStore [root container]
  Store
  (present?
    [store key]
    (let [path (str root (join-path [container (name key)]))
          file (io/file path)]
      (.exists file)))
  (protocol [store] "file://")
  (partition-data
    [store data])
  (existing-keys
    [store path]
    (let [base (str root container "/")
          files (kafka/dir->files base)]
      (mapv (partial file->key base) files))))

(defn absent?
  [store key]
  (not (present? store key)))

(defn existing-paths
  [store root]
  (let [existing (existing-keys store root)]
    (into
     {}
     (map
      (fn [key]
        [key {:state :complete}])
      existing))))

(defn load-file-store
  [config container]
  (FileStore. (:root config) container))

(defn file-store-generator
  [config]
  (fn [container]
    (load-file-store config container)))
