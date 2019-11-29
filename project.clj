(defproject gaia "0.0.16"
  :description "workflow server"
  :url "http://github.com/prismofeverything/gaia"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :main gaia.core
  :pedantic? false
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/math.combinatorics "0.1.5"]
                 [ring "1.7.1"]
                 [aleph "0.4.6"]
                 [clj-http "3.10.0"]
                 [ubergraph "0.5.2"]
                 [protograph "0.0.23"]
                 [polaris "0.0.19"]
                 [sisyphus "0.0.17"]
                 [com.google.guava/guava "28.0-jre"]
                 [org.javaswift/joss "0.9.17"]])
