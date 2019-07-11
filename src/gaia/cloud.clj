(ns gaia.cloud
  (:require
   [clojure.string :as string]
   [sisyphus.cloud :as cloud]
   [gaia.store :as store])
  (:import
   [com.google.cloud.storage
    BlobId
    Storage$BlobListOption]))

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

(defn list-directory
  [storage bucket directory]
  (let [options (directory-options directory)
        blobs (.list storage bucket options)]
    (map
     (fn [x]
       (str bucket ":" (.getName x)))
     (.iterateAll blobs))))

(defn split-path
  [key]
  (let [colon (.indexOf key ":")]
    [(.substring key 0 colon)
     (.substring key (inc colon))]))

(deftype CloudStore [storage container]
  store/Store
  (present?
    [store key]
    (let [[bucket path] (split-path key)]
      (exists? storage bucket (store/join-path [container path]))))
  (protocol [store] "")
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
