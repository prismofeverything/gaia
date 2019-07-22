(ns gaia.executor
  (:require
   [cheshire.core :as json]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]))

(defprotocol Executor
  (submit! [executor commands task])
  (cancel! [executor id]))

(defn declare-event!
  [producer message log-event]
  (log/warn! log-event (name (:workflow message))) ; notice would be more fitting but Logs Viewer shows it like info
  (kafka/send!
   producer
   "gaia-events"
   message))

;; TODO: make local docker execution task type
