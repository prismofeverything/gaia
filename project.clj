(defproject gaia "0.0.10"
  :description "regenerating dependency network"
  :url "http://github.com/prismofeverything/gaia"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :main gaia.core
  :pedantic? false
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/math.combinatorics "0.1.5"]
                 [com.taoensso/timbre "4.8.0"]
                 [ring "1.7.1"]
                 [aleph "0.4.6"]
                 [clj-http "3.7.0"]
                 [ubergraph "0.5.2"]
                 [protograph "0.0.19"]
                 [polaris "0.0.19"]
                 [sisyphus "0.0.6"]
                 [com.google.guava/guava "23.6-jre"]
                 [org.javaswift/joss "0.9.17"]])
