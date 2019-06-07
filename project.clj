(defproject gaia "0.0.9"
  :description "regenerating dependency network"
  :url "http://github.com/prismofeverything/gaia"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :main gaia.core
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/math.combinatorics "0.1.5"]
                 [com.taoensso/timbre "4.8.0"]
                 [aleph "0.4.4"]
                 [clj-http "3.7.0"]
                 [ubergraph "0.5.2"]
                 [ophion "0.0.13"]
                 [sisyphus "0.0.4"]
                 [com.google.guava/guava "23.6-jre"]
                 [org.javaswift/joss "0.9.17"]])
