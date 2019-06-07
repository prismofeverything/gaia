{:kafka
 {:base
  {:host "localhost"
   :port "9092"}}

 :mongo
 {:host "127.0.0.1"
  :port 27017
  :database "test"}

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
