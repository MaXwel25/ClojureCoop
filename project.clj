(defproject mire-arena "0.1.0-SNAPSHOT"
  :description "Multiplayer arena game inspired by Mire"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [http-kit "2.5.3"] ; для web-socket 
                 [cheshire "5.10.0"]
                 [quil "2.8.0"]] ; для графики
  :main ^:skip-aot mire-arena.core ; точка входа
  :target-path "target/%s"
  :resource-paths ["resources/statics"]
  
  :jvm-opts ["-Xmx512m" "-XX:+UseG1GC"] ; сборщик мусора
  
  :profiles {:uberjar {:aot :all
                       :uberjar-name "Mire-Arena.jar"}}
  
  :aliases {"server" ["run" "server"]
            "client" ["run" "client"]
            "build-uberjar" ["uberjar"]})
