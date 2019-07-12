(ns gaia.cloud
  (:require
   [clojure.string :as string]
   [sisyphus.cloud :as cloud]
   [gaia.store :as store])
  (:import
   [com.google.cloud.storage
    BlobId
    Storage$BlobListOption]))

(defn split-path
  [key]
  (let [colon (.indexOf key ":")]
    [(.substring key 0 colon)
     (.substring key (inc colon))]))

(defn exists?
  [storage bucket key]
  (let [blob-id (BlobId/of bucket key)
        blob (.get storage blob-id)]
    (.exists blob)))

(defn directory-options
  [directory]
  (into-array
   Storage$BlobListOption
   [(Storage$BlobListOption/prefix directory)]))

(defn attain-list
  [storage bucket directory]
  (let [options (directory-options directory)]
    (.list storage bucket options)))

(defn list-directory
  [storage bucket directory]
  (let [blobs (attain-list storage bucket directory)]
    (map
     (fn [x]
       (str bucket ":" (.getName x)))
     (.iterateAll blobs))))

(defn partition-keys
  [storage data]
  (group-by
   (fn [key]
     (let [[bucket path] (split-path key)
           blobs (attain-list storage bucket path)]
       (not (.isEmpty (.getValues blobs)))))
   data))

(deftype CloudStore [storage container]
  store/Store
  (present?
    [store key]
    (let [[bucket path] (split-path key)]
      (exists? storage bucket (store/join-path [container path]))))
  (protocol [store] "")
  (partition-data
    [store data]
    (let [result (partition-keys storage data)]
      [(get result true) (get result false)]))
  (existing-keys
    [store root]
    (let [[bucket path] (split-path root)]
      (list-directory storage bucket path))))

(defn load-cloud-store
  [config container]
  (let [storage (cloud/connect-storage! config)
        store (CloudStore. storage container)]
    store))

(defn cloud-store-generator
  [config]
  (fn [container]
    (load-cloud-store config container)))
