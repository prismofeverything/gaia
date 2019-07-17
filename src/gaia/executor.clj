(ns gaia.executor
  (:require
   [cheshire.core :as json]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]))

(defprotocol Executor
  (submit! [executor commands process])
  (cancel! [executor id]))

(defn declare-event!
  [producer message]
  (log/info! "declare event" message)
  (kafka/send!
   producer
   "gaia-events"
   message))

;; TODO: make local docker execution task type
