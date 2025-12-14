(ns mire-arena.core
  (:gen-class)
  (:require [mire-arena.server :as server]
            [mire-arena.client :as client]))

(defn print-banner []
  (println "
   __  ___ ___      ___                      
  /  |/  // _ \\    / _ \\___ ____  ___ ____  
 / /|_/ // ___/   / , _/ _ `/ _ \\/ _ `/ _ \\ 
/_/  /_//_/      /_/|_|\\_,_/_//_/\\_, /_//_/ 
                                 /___/       
  ")
  (println "🎮 Mire Arena: Чат и взаимодействие")
  (println "=================================="))

(defn -main
  "Запускает игру в режиме сервера или клиента"
  [& args]
  (print-banner)
  
  (let [mode (first args)]
    (case mode
      "server" (do
                (println "🚀 Запуск сервера...")
                (server/start))
      
      "client" (do
                (println "🎮 Запуск клиента...")
                (client/start))
      
      (do
        (println "Использование:")
        (println "  lein run server  - запустить сервер")
        (println "  lein run client  - запустить клиент")
        (System/exit 0)))))
