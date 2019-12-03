(ns gaia.core
  (:require
   [clojure.walk :as walk]
   [clojure.string :as string]
   [clojure.tools.cli :as cli]
   [aleph.http :as http]
   [ring.middleware.resource :as resource]
   [ring.middleware.params :as params]
   [ring.middleware.keyword-params :as keyword]
   [cheshire.core :as json]
   [ubergraph.core :as graph]
   [polaris.core :as polaris]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]
   [sisyphus.rabbit :as rabbit]
   [gaia.config :as config]
   [gaia.store :as store]
   [gaia.executor :as executor]
   [gaia.command :as command]
   [gaia.flow :as flow]
   [gaia.sync :as sync])
  (:import
   [java.io InputStreamReader]))

(defn atom?
  [x]
  (instance? clojure.lang.IAtom x))

(defn read-json
  [body]
  (json/parse-stream (InputStreamReader. body) keyword))

(defn json-seq
  [path]
  (map
   #(json/parse-string % keyword)
   (string/split (slurp path) #"\n")))

(defn json-response
  [body]
  {:status 200
   :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

(defn serializable
  "Make internal data JSON-serializable by expanding each atom's value. This is
  useful for debugging but doesn't promise API results that client code can
  depend on."
  [data]
  (walk/prewalk
   (fn [node]
     (if (atom? node)
       @node
       node))
   data))

(defn index-handler
  [state]
  (fn [request]
    {:status 200
     :headers {"content-type" "text/html"}
     :body (slurp "resources/public/index.html")}))

(defn initialize-flow!
  "Construct the named workflow, connected to storage and kafka messaging."
  [{:keys [store kafka] :as state} workflow]
  (when-not (seq (name workflow))
    (throw (IllegalArgumentException. "empty workflow name")))
  (let [pointed (store (name workflow))]
    (sync/generate-sync workflow kafka pointed)))

(defn find-flow!
  "Find or construct the named workflow."
  [{:keys [flows] :as state} workflow]
  (if-let [flow (get @flows (keyword workflow))]
    flow
    (let [flow (initialize-flow! state workflow)]
      (swap! flows assoc workflow flow)
      flow)))

(defn handle-kafka
  [{:keys [executor] :as state} topic message]
  (let [flow (find-flow! state (:workflow message))]
    (sync/executor-events! flow executor topic message)))

(defn executor-status!
  [{:keys [kafka] :as state}]
  (let [status-topic (get kafka :status-topic "gaia-status")
        topics ["gaia-events" status-topic]
        kafka (update kafka :subscribe concat topics)
        handle (partial handle-kafka state)]
    (kafka/boot-consumer kafka handle)))

(defn boot
  [config]
  (let [flows (atom {})
        store (config/load-store (:store config))
        rabbit (rabbit/connect! (:rabbit config))
        kafka (:kafka config)
        producer (kafka/boot-producer kafka)
        kafka (assoc kafka :producer producer)
        exec-config (assoc
                     (:executor config)
                     :kafka kafka
                     :rabbit rabbit)
        executor (config/load-executor exec-config)
        state {:config config
               :rabbit rabbit
               :kafka kafka
               :flows flows
               :store store
               :executor executor}]
    (assoc-in
     state
     [:kafka :consumer]
     (executor-status! state))))

(defn merge-properties!
  "Merge the new properties into the named workflow."
  [state workflow properties]
  (let [flow (find-flow! state workflow)]
    (sync/merge-properties! flow properties)
    state))

(defn merge-commands!
  "Merge the new commands `merging` into the named workflow."
  [{:keys [executor] :as state} workflow merging]
  (let [flow (find-flow! state workflow)]
    (sync/merge-commands! flow executor merging)
    state))

(defn merge-steps!
  "Merge the new steps into the named workflow."
  [{:keys [executor] :as state} workflow steps]
  (let [flow (find-flow! state workflow)]
    (sync/merge-steps! flow executor steps)
    state))

;(defn load-steps!
;  [state workflow path]
;  (let [steps (config/parse-yaml path)]
;    (merge-steps! state workflow steps)))

(defn run-flow!
  [{:keys [executor] :as state} workflow]
  (let [flow (find-flow! state workflow)]
    (sync/run-flow! flow executor)
    state))

(defn halt-flow!
  [{:keys [executor] :as state} workflow]
  (let [flow (find-flow! state workflow)]
    (sync/halt-flow! flow executor)
    state))

(defn expire-keys!
  [{:keys [executor] :as state} workflow expire]
  (when (seq expire)
    (log/info! "expiring storage path keys" expire "of" workflow))
  (let [flow (find-flow! state workflow)]
    (sync/expire-keys! flow executor expire)
    state))

(defn flow-status!
  [state workflow debug]
  (let [flow (find-flow! state workflow)
        {:keys [state data tasks]} @(:status flow)
        complete (sync/complete-keys data)
        status {:state state
                :commands @(:commands flow)
                ; TODO(jerry): Add :steps.
                :waiting (flow/missing-data @(:flow flow) complete)}
        status (if debug ; include internal guts
                 (merge status
                        (serializable
                         {:flow @(:flow flow)
                          :tasks tasks
                          :data data}))
                 status)]
    status))

(defn workflows-info
  "Return a map of workflow names to their summary info."
  [{:keys [flows] :as state}]
  (into {}
        (map (fn [[workflow flow]] [workflow (sync/summarize-flow flow)])
             @flows)))

(defn command-handler
  "Merge the given commands (transformed to a keyword -> value map `index`) into
  the named workflow."
  [state]
  (fn [request]
    (let [{:keys [workflow commands] :as body} (read-json (:body request))
          workflow (keyword workflow)
          index (command/index-key :name commands)]
      (log/info! "merge commands request" body)
      (merge-commands! state workflow index)
      (json-response
       {:commands
        (deref
         (:commands
          (find-flow! state workflow)))}))))

(defn merge-handler
  "Merge the given steps into the named workflow."
  [state]
  (fn [request]
    (let [{:keys [workflow steps] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "merge steps request" body)
      (merge-steps! state workflow steps)
      ; TODO(jerry): Return ALL the steps or at least all their names.
      (json-response
       {:steps (map :name steps)}))))

(defn upload-handler
  "Upload a new workflow in one request: properties, commands, and steps."
  [{:keys [flows] :as state}]
  (fn [request]
    (let [{:keys [workflow properties commands steps] :as body}
          (read-json (:body request))
          workflow (keyword workflow)
          index (command/index-key :name commands)]
      (log/info! "upload workflow request" body)
      (when (get @flows workflow)
        (throw (IllegalArgumentException.
                 (str "workflow already exists: " (name workflow)))))

      (merge-properties! state workflow properties)
      (merge-commands! state workflow index)
      (merge-steps! state workflow steps)
      (json-response
       {:workflow {workflow (map :name steps)}}))))

(defn run-handler
  "Trigger the named workflow if it's not already running."
  [state]
  (fn [request]
    (let [{:keys [workflow] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "run request" body)
      (run-flow! state workflow)
      (json-response
       {:run workflow}))))

(defn halt-handler
  "Immediately stop the named workflow and cancel its running tasks."
  [state]
  (fn [request]
    (let [{:keys [workflow] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "halt request" body)
      (halt-flow! state workflow)
      (json-response
       {:halt workflow}))))

(defn status-handler
  "Return information about the named workflow."
  [state]
  (fn [request]
    (let [{:keys [workflow debug] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "status request" body)
      (json-response
       {:workflow workflow
        :status
        (flow-status! state workflow debug)}))))

(defn expire-handler
  "Expire the named steps and/or data files (by storage keys) from the named
  workflow."
  [state]
  (fn [request]
    (let [{:keys [workflow expire] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "expire request" body)
      (expire-keys! state workflow expire)
      (json-response
       {:expire expire}))))

(defn workflows-handler
  "List the current workflows in a map with summary info about each one."
  [state]
  (fn [request]
    (log/info! "list workflows request")
    (json-response {:workflows (workflows-info state)})))

(defn gaia-routes
  [state]
  [["/" :index (index-handler state)]
   ["/command" :command (command-handler state)]
   ["/merge" :merge (merge-handler state)]
   ["/upload" :upload (upload-handler state)]
   ["/run" :run (run-handler state)]
   ["/halt" :halt (halt-handler state)]
   ["/status" :status (status-handler state)]
   ["/expire" :expire (expire-handler state)]
   ["/workflows" :workflows (workflows-handler state)]])

(def parse-args
  [["-c" "--config CONFIG" "path to config file"]
   ["-i" "--input INPUT" "input file or directory"]])

(defn wrap-error
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/exception! e "bad request" request)
        (json-response
         {:error "bad request"
          :request request})))))

(defn start
  [options]
  (let [path (or (:config options) "resources/config/gaia.clj")
        config (config/read-path path)
        state (boot config)
        routes (polaris/build-routes (gaia-routes state))
        router (polaris/router routes)
        app (-> router
                (resource/wrap-resource "public")
                (keyword/wrap-keyword-params)
                (params/wrap-params)
                (wrap-error))]
    (println config)
    (http/start-server app {:port 24442})
    state))

;(defn load-yaml
;  [key config-path step-path]
;  (let [config (config/read-path config-path)
;        state (boot config)
;        state (load-steps! state key step-path)]
;    (run-flow! state key)))

(defn -main
  [& args]
  (let [env (:options (cli/parse-opts args parse-args))]
    (start env)))
