(ns gaia.cloud
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [sisyphus.cloud :as cloud]
   [gaia.store :as store]))

(deftype CloudStore [storage container]
  store/Store
  (present?
    [store key]
    (let [[bucket path] (cloud/split-key key)]
      (cloud/exists? storage bucket (store/join-path [container path]))))
  (protocol [store] "")
  (partition-data
    [store data]
    (cloud/partition-keys storage data))
  (existing-keys
    [store root]
    (let [[bucket path] (cloud/split-key root)]
      (cloud/list-directory storage bucket path))))

(defn load-cloud-store
  [config container]
  (let [storage (cloud/connect-storage! config)
        store (CloudStore. storage container)]
    store))

(defn cloud-store-generator
  [config]
  (fn [container]
    (load-cloud-store config container)))
