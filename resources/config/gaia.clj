{:kafka
 {:host "localhost"
  :port "9092"
  :status-topic "sisyphus-status"}

 :executor
 {:target "sisyphus"
  :path ""
  :rabbit
  {:host "localhost"}
  :project "my-gcloud-project"
  :zone "us-west1-b"}

 :store
 {:type :cloud
  :root ""}

 :flow
 {:path "resources/test/wcm/wcm"}}
