(ns gaia.swift
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]
   [gaia.store :as store])
  (:import
   [org.javaswift.joss.client.factory AccountFactory]))

(defn get-container
  [account container]
  (.getContainer account container))

(defn container-exists?
  [container]
  (.exists container))

(defn swift-connect
  [{:keys [username password url method tenant-id tenant-name region root]}]
  (let [factory (AccountFactory.)]
    (.setUsername factory username)
    (.setPassword factory password)
    (.setAuthUrl factory url)
    (if region (.setPreferredRegion factory region))
    (if tenant-id (.setTenantId factory tenant-id))
    (if tenant-name (.setTenantName factory tenant-name))
    (if method (.setAuthenticationMethod factory method))
    (let [account (.createAccount factory)]
      {:account account
       :root root})))

(defn get-object
  [container key]
  (.getObject container (name key)))

(defn key-exists?
  [{:keys [container]} key]
  (let [object (get-object container key)]
    (.exists object)))

(def encoded-slash #"%2F")
(def encoded-colon #"%3A")

(defn path-for
  [prefix object]
  (-> (.getPath object)
      (string/replace encoded-slash "/")
      (string/replace encoded-colon ":")
      (string/replace prefix "")))

(defn all-keys
  [{:keys [container container-name] :as swift}]
  (loop [dirs (.listDirectory container)
         all []]
    (if (empty? dirs)
      all
      (let [head (first dirs)
            remaining (rest dirs)
            base (re-pattern (str "/" container-name "/"))]
        (if (.isDirectory head)
          (recur
           (concat remaining (.listDirectory container head))
           all)
          (recur
           remaining
           (conj all (path-for base head))))))))

(defn create-container
  [account path]
  (let [container (.getContainer account path)]
    (.create container)
    (.makePublic container)
    container))

(defn list-containers
  [account]
  (let [containers (.list account)]
    (map #(.getName %) containers)))

(defn change-container
  [{:keys [account] :as swift} container]
  (merge
   swift
   {:container-name container
    :container (get-container account container)}))

(defn put-key
  [{:keys [container]} key path]
  (let [object (get-object container key)
        file (io/file path)]
    (.uploadObject object file)))

(defn get-key
  [{:keys [container]} key path]
  (let [object (get-object container key)
        file (io/file path)]
    (store/ensure-path path)
    (if (.exists file)
      (log/info "already downloaded" path)
      (try
        (.downloadObject object file)
        (catch Exception e
          (log/info key "failed to download")
          (.getMessage e))))))

(defn get-path
  [{:keys [container container-name] :as swift} key path]
  (let [all (all-keys swift)
        pattern (re-pattern (name key))
        matches (filter (partial re-find pattern) all)
        matches (sort-by count > matches)]
    (doseq [match matches]
      (log/info "downloading" match)
      (get-key swift match (str path "/" match)))
    matches))

(defn delete-key
  [{:keys [container]} key]
  (let [object (get-object container key)]
    (.delete object)))

(defn delete-path
  [{:keys [container container-name] :as swift} key]
  (let [all (all-keys swift)
        pattern (re-pattern (name key))
        matches (filter (partial re-find pattern) all)
        matches (sort-by count > matches)]
    (doseq [match matches]
      (delete-key swift match))
    matches))

(deftype SwiftStore [swift]
  store/Store
  (present?
    [store key]
    (key-exists? swift key))
  (protocol [store] (str "swift://" (:container-name swift)))
  (existing-keys
    [store path]
    (all-keys swift)))

(defn load-swift-store
  [config]
  (let [swift (swift-connect config)]
    (SwiftStore. swift)))

(defn swift-store-generator
  [config]
  (let [swift (swift-connect config)]
    (fn [container]
      (let [pointed (change-container swift container)]
        (SwiftStore. pointed)))))
