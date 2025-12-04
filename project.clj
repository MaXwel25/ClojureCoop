(defproject mire-arena "0.1.0-SNAPSHOT"
  :description "Multiplayer arena game inspired by Mire"
  :url "https://github.com/yourusername/mire-arena"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [play-clj "1.4.5"] ; Clojure обертка для libGDX [[1]]
                 [com.badlogicgames.gdx/gdx-backend-lwjgl3 "1.12.0"]
                 [com.badlogicgames.gdx/gdx-platform "1.12.0"
                  :classifier "natives-desktop"]
                 [com.badlogicgames.gdx/gdx-box2d "1.12.0"]
                 [com.badlogicgames.gdx/gdx-box2d-platform "1.12.0"
                  :classifier "natives-desktop"]
                 [ring "1.9.5"]
                 [aleph "0.5.0"]]
  :main ^:skip-aot mire-arena.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
