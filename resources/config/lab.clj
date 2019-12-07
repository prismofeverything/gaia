{:kafka
 {:host "localhost"
  :port "9092"
  :status-topic "sisyphus-status"}

 :rabbit
 {}

 :executor
 {:target "sisyphus"
  :path ""}

 :store
 {:type :cloud
  :root ""}

 :flow
 {:path "resources/test/wcm/wcm"}}
