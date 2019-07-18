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

(defn response
  [body]
  (let [deatomized (walk/postwalk
                    (fn [node]
                      (if (atom? node)
                        @node
                        node))
                    body)]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (json/generate-string deatomized)}))

(defn index-handler
  [state]
  (fn [request]
    {:status 200
     :headers {"content-type" "text/html"}
     :body (slurp "resources/public/index.html")}))

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
        executor (config/load-executor exec-config)]
    {:config config
     :rabbit rabbit
     :kafka kafka
     :flows flows
     :store store
     :executor executor}))

(defn initialize-flow!
  [{:keys [executor store kafka] :as state} workflow]
  (let [pointed (store (name workflow))]
    (sync/initialize-flow! workflow pointed executor kafka)))

(defn find-flow!
  [{:keys [flows] :as state} workflow]
  (if-let [flow (get @flows workflow)]
    flow
    (let [flow (initialize-flow! state workflow)]
      (swap! flows assoc workflow flow)
      flow)))

(defn merge-commands!
  [{:keys [flows executor] :as state} workflow merging]
  (let [flow (find-flow! state workflow)]
    (sync/merge-commands! flow executor merging)
    state))

(defn merge-steps!
  [{:keys [executor] :as state} workflow steps]
  (let [flow (find-flow! state workflow)]
    (sync/merge-steps! flow executor steps)
    state))

(defn load-steps!
  [state workflow path]
  (let [steps (config/parse-yaml path)]
    (merge-steps! state workflow steps)))

(defn run-flow!
  [{:keys [executor flows] :as state} workflow]
  (let [flow (find-flow! state workflow)]
    (sync/run-flow! flow executor)
    state))

(defn halt-flow!
  [{:keys [executor tasks] :as state} workflow]
  (let [flow (find-flow! state workflow)]
    (sync/halt-flow! flow executor)
    state))

(defn expire-keys!
  [{:keys [executor] :as state} workflow expire]
  (log/info! "expiring keys" workflow expire)
  (let [flow (find-flow! state workflow)]
    (sync/expire-keys! flow executor expire)))

(defn flow-status!
  [state workflow]
  (let [flow (find-flow! state workflow)
        {:keys [state data]} @(:status flow)
        complete (sync/complete-keys data)
        status {:state state
                :flow @(:flow flow)
                :tasks @(:tasks flow)
                :commands @(:commands flow)
                :waiting (flow/missing-data @(:flow flow) complete)
                :data data}]
    (println "STATUS" status)
    status))

(defn command-handler
  [state]
  (fn [request]
    (let [{:keys [workflow commands] :as body} (read-json (:body request))
          workflow (keyword workflow)
          index (command/index-key commands)]
      (log/info! "commands request" body)
      (merge-commands! state workflow index)
      (response
       {:commands
        (deref
         (:commands
          (find-flow! state workflow)))}))))

(defn merge-handler
  [state]
  (fn [request]
    (let [{:keys [workflow steps] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "merge request" body)
      (merge-steps! state workflow steps)
      (response
       {:steps {workflow (map :key steps)}}))))

(defn run-handler
  [state]
  (fn [request]
    (let [{:keys [workflow] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "run request" body)
      (run-flow! state workflow)
      (response
       {:run workflow}))))

(defn halt-handler
  [state]
  (fn [request]
    (let [{:keys [workflow] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "halt request" body)
      (halt-flow! state workflow)
      (response
       {:halt workflow}))))

(defn status-handler
  [state]
  (fn [request]
    (let [{:keys [workflow] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "status request" body)
      (response
       {:workflow workflow
        :status
        (flow-status! state workflow)}))))

(defn expire-handler
  [state]
  (fn [request]
    (let [{:keys [workflow expire] :as body} (read-json (:body request))
          workflow (keyword workflow)]
      (log/info! "expire request" body)
      (response
       {:expire
        (expire-keys! state workflow expire)}))))

(defn gaia-routes
  [state]
  [["/" :index (index-handler state)]
   ["/command" :command (command-handler state)]
   ["/merge" :merge (merge-handler state)]
   ["/run" :run (run-handler state)]
   ["/halt" :halt (halt-handler state)]
   ["/status" :status (status-handler state)]
   ["/expire" :expire (expire-handler state)]])

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
        (response
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
                (params/wrap-params))]
    (println config)
    (http/start-server app {:port 24442})
    state))

(defn load-yaml
  [key config-path step-path]
  (let [config (config/read-path config-path)
        state (boot config)
        state (load-steps! state key step-path)]
    (run-flow! state key)))

(defn -main
  [& args]
  (let [env (:options (cli/parse-opts args parse-args))]
    (start env)))
