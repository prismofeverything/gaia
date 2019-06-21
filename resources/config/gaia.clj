{:kafka
 {:host "localhost"
  :port "9092"
  :status-topic "sisyphus-status"}

 :mongo
 {:host "127.0.0.1"
  :port 27017
  :database "test"}

 :rabbit
 {:host "localhost"}

 :executor
 {:target "sisyphus"
  :path ""}

 :store
 {:type :cloud
  :root ""}

 :flow
 {:path "resources/test/wcm/wcm"}}
