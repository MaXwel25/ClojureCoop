(defproject mire-arena "0.1.0-SNAPSHOT"
  :description "Multiplayer arena game inspired by Mire"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.673"]
                 [http-kit "2.6.0"] ; для web-socket 
                 [cheshire "5.10.0"]
                 [quil "2.8.0"]] ; для графики
  :main mire-arena.core ; точка входа
  :target-path "target/%s"
  :resource-paths ["resources"]

  :jvm-opts ["-Xmx512m" "-XX:+UseG1GC" "-Dclojure.compiler.direct-linking=true"] ; сборщик мусора

  :aot [mire-arena.core]

  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "1.3.0"]]}}

  :aliases {"server" ["run" "server"]
            "client" ["run" "client"]})