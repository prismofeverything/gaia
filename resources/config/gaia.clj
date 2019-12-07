{:kafka
 {:host "localhost"
  :port "9092"
  :status-topic "sisyphus-status"}

 :rabbit
 {:host "localhost"}

 :executor
 {:target "sisyphus"
  :path ""
  :rabbit {}
  :project "gcloud-project"
  :zone "us-west1-b"}

 :store
 {:type :cloud
  :root ""}

 :flow
 {:path "resources/test/wcm/wcm"}}
