(ns gaia.core
  (:require
   [clojure.walk :as walk]
   [clojure.string :as string]
   [clojure.tools.cli :as cli]
   [taoensso.timbre :as log]
   [aleph.http :as http]
   [ring.middleware.resource :as resource]
   [ring.middleware.params :as params]
   [ring.middleware.keyword-params :as keyword]
   [cheshire.core :as json]
   [ubergraph.core :as graph]
   [polaris.core :as polaris]
   [sisyphus.kafka :as kafka]
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
  (let [commands (atom (:commands config))
        flows (atom {})
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
     :commands commands
     :flows flows
     :store store
     :executor executor}))

(defn initialize-flow!
  [{:keys [commands executor store kafka] :as state} root]
  (let [pointed (store (name root))]
    (sync/initialize-flow! root pointed executor kafka commands)))

(defn find-flow!
  [{:keys [flows] :as state} root]
  (if-let [flow (get @flows root)]
    flow
    (let [flow (initialize-flow! state root)]
      (swap! flows assoc root flow)
      flow)))

(defn merge-commands!
  [{:keys [flows commands executor] :as state} merging]
  (let [prior (map name (keys (select-keys @commands (keys merging))))]
    (log/info "prior commands" (into [] prior))
    (doseq [[key flow] @flows]
      (sync/expire-commands! flow executor commands prior))
    (swap! commands merge merging)))

(defn merge-processes!
  [{:keys [commands executor] :as state} root processes]
  (let [flow (find-flow! state root)]
    (sync/merge-processes! flow executor commands processes)
    state))

(defn load-processes!
  [state root path]
  (let [processes (config/parse-yaml path)]
    (merge-processes! state root processes)))

(defn trigger-flow!
  [{:keys [commands executor flows] :as state} root]
  (let [flow (find-flow! state root)]
    (sync/trigger-flow! flow root executor commands)
    state))

(defn halt-flow!
  [{:keys [executor tasks] :as state} root]
  (let [flow (find-flow! state root)]
    (sync/halt-flow! flow executor)
    state))

(defn expire-keys!
  [{:keys [commands executor] :as state} root expire]
  (log/info "expiring keys" root expire)
  (let [flow (find-flow! state root)]
    (log/info "expiring flow" flow)
    (sync/expire-keys! flow executor commands expire)))

(defn flow-status!
  [state root]
  (let [flow (find-flow! state root)
        {:keys [state data]} @(:status flow)
        status {:state state
                :flow @(:flow flow)
                :tasks @(:tasks flow)
                :data data}]
    (println "STATUS" status)
    status))

(defn command-handler
  [state]
  (fn [request]
    (let [{:keys [commands] :as body} (read-json (:body request))
          index (command/index-key commands)]
      (log/info "commands request" body)
      (merge-commands! state index)
      (response
       {:commands @(:commands state)}))))

(defn merge-handler
  [state]
  (fn [request]
    (let [{:keys [root processes] :as body} (read-json (:body request))
          root (keyword root)]
      (log/info "merge request" body)
      (merge-processes! state root processes)
      (response
       {:processes {root (map :key processes)}}))))

(defn trigger-handler
  [state]
  (fn [request]
    (let [{:keys [root] :as body} (read-json (:body request))
          root (keyword root)]
      (log/info "trigger request" body)
      (trigger-flow! state root)
      (response
       {:trigger root}))))

(defn halt-handler
  [state]
  (fn [request]
    (let [{:keys [root] :as body} (read-json (:body request))
          root (keyword root)]
      (log/info "halt request" body)
      (halt-flow! state root)
      (response
       {:halt root}))))

(defn status-handler
  [state]
  (fn [request]
    (let [{:keys [root] :as body} (read-json (:body request))
          root (keyword root)]
      (log/info "status request" body)
      (response
       {:root root
        :status
        (flow-status! state root)}))))

(defn expire-handler
  [state]
  (fn [request]
    (let [{:keys [root expire] :as body} (read-json (:body request))
          root (keyword root)]
      (log/info "expire request" body)
      (response
       {:expire
        (expire-keys! state root expire)}))))

(defn gaia-routes
  [state]
  [["/" :index (index-handler state)]
   ["/command" :command (command-handler state)]
   ["/merge" :merge (merge-handler state)]
   ["/trigger" :trigger (trigger-handler state)]
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
        (println "bad request -" request)
        (response
         {:error "bad request"
          :request request})))))

(defn start
  [options]
  (let [path (or (:config options) "resources/config/gaia.clj")
        config (config/load-config path)
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
  [key config-path process-path]
  (let [config (config/load-config config-path)
        state (boot config)
        state (load-processes! state key process-path)]
    (trigger-flow! state key)))

(defn -main
  [& args]
  (let [env (:options (cli/parse-opts args parse-args))]
    (start env)))
