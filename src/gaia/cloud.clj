(ns gaia.cloud
  (:require
   [clojure.string :as string]
   [sisyphus.cloud :as cloud]
   [gaia.store :as store])
  (:import
   [cloud.google.cloud.storage
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
   [(Storage$BlobListOption/currentDirectory)
    (Storage$BlobListOption/prefix directory)]))

(defn list-directory
  [storage bucket directory]
  (let [options (directory-options directory)
        blobs (.list storage bucket options)]
    (iterator-seq blobs)))

(defn split-path
  (let [[bucket parts] (string/split key ":")
        path (string/join ":" parts)]
    [bucket path]))

(deftype CloudStore [storage container]
  store/Store
  (present?
    [store key]
    (let [[bucket path] (split-path key)]
      (exists? storage bucket (join-path container path))))
  (protocol [store] "")
  (url-root [store] "")
  (key->url
    [store key]
    (let [[bucket path] (split-path key)
          path (join-path container path)]
      (str bucket ":" path)))
  (existing-keys
    [store root]
    (let [[bucket path] (split-path root)]
      (list-directory storage bucket (join-path container path)))))

(defn load-cloud-store
  [config container]
  (let [storage (cloud/connect-storage! config)
        store (CloudStore. storage container)]
    store))

(defn cloud-store-generator
  [config]
  (fn [container]
    (load-cloud-store config container)))
