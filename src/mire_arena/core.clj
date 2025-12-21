(ns mire-arena.core
  (:require [mire-arena.server :as server]
            [mire-arena.bot :as bot]
            [mire-arena.network-utils :as net]
            [clojure.string :as str]))

(defn get-local-ip []
  (try
    (net/get-local-ip)
    (catch Exception e
      (println " Could not determine local IP:" (.getMessage e))
      "localhost")))

(defn print-server-info []
  (let [local-ip (get-local-ip)]
    (println "
       __  ___ ___      ___                      
      /  |/  // _ \\    / _ \\___ ____  ___ ____  
     / /|_/ // ___/   / , _/ _ `/ _ \\/ _ `/ _ \\ 
    /_/  /_//_/      /_/|_|\\_,_/_//_/\\_, /_//_/ 
                                     /___/       
      ")
    (println "ARENA SERVER STARTED SUCCESSFULLY!")
    (println " ")
    (println "Local connection: localhost:8080")
    (println "Network connection:" local-ip ":8080")
    (println "")
    (println "GAME FEATURES:")
    (println "   • Boss with 1000 HP and AI (Prolog)")
    (println "   • Multiplayer arena combat")
    (println "   • Health, Speed, and Damage bonuses")
    (println "   • Real-time WebSocket communication")
    (println "")
    (println "Players can connect using:")
    (println "   - IP address:" local-ip)
    (println "   - Hostname: localhost")
    (println "")
    (println "Controls: WASD + Mouse | F3: Debug | Ctrl+R: Reconnect")
    (println "")
    (println "Press Ctrl+C to stop the server")))

(defn print-client-info [server-ip]
  (println "
     __  ___ ___      ___                      
    /  |/  // _ \\    / _ \\___ ____  ___ ____  
   / /|_/ // ___/   / , _/ _ `/ _ \\/ _ `/ _ \\ 
  /_/  /_//_/      /_/|_|\\_,_/_//_/\\_, /_//_/ 
                                   /___/       
    ")
  (println " ARENA CLIENT STARTING...")
  (println " ============================")
  (println " Connecting to server:" server-ip)
  (println "")
  (println " GAME FEATURES:")
  (println "   • Battle against AI boss with 1000 HP")
  (println "   • Multiplayer PvP and PvE combat")
  (println "   • Collect bonuses for advantages")
  (println "   • Real-time action with smooth controls")
  (println "")
  (println " CONTROLS:")
  (println "   Movement: WASD keys")
  (println "   Sprint: Hold Shift")
  (println "   Shoot: Mouse click or Arrow keys")
  (println "   Aim: Mouse position")
  (println "   Reconnect: Ctrl+R")
  (println "")
  (println " TIP: Focus the game window and defeat the boss!"))

;; вывод информации
(defn validate-ip-address [ip]
  (or (= ip "localhost")
      (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" ip)))

(defn discover-servers []
  (println " Searching for local game servers...")
  (println "   No automatic discovery implemented yet.")
  (println "   Please enter server IP manually.")
  [])

(defn interactive-server-selection []
  (println "")
  (println " SERVER SELECTION")
  (println "1 - Connect to localhost")
  (println "2 - Enter server IP manually")
  (print "Choose option (1 or 2): ")
  (flush)

  (let [choice (read-line)]
    (case choice
      "1" "localhost"
      "2" (do
            (print "Enter server IP: ")
            (flush)
            (let [ip (read-line)]
              (if (validate-ip-address ip)
                ip
                (do
                  (println " Invalid IP address. Using localhost.")
                  "localhost")))) 
      "localhost")))

(defn start-server-mode []
  (println " ")
  (println "STARTING ARENA SERVER...")
  (println " ")

  (server/start-server)
  (print-server-info)

  (println " ")
  (println "Server is running. Press Ctrl+C to stop...")

  (try
    (while true
      (Thread/sleep 1000))
    (catch InterruptedException e
      (println " ")
      (println " Server shutdown requested...")
      (server/stop-server)
      (println " Server stopped successfully"))))

(defn start-client-mode [server-ip]
  (println "")
  (println " STARTING ARENA CLIENT...")
  (println " ")

  (let [final-ip (if server-ip
                   server-ip
                   (interactive-server-selection))]

    (when (not (validate-ip-address final-ip))
      (println "Invalid server address:" final-ip)
      (println "Using localhost instead")
      (start-client-mode "localhost"))

    (when (validate-ip-address final-ip)
      (print-client-info final-ip)

      (try
        (require 'mire-arena.client)
        (println " ")
        (println "Client loaded successfully")
        (println "Starting game interface...")

        (Thread/sleep 1000)

        ((resolve 'mire-arena.client/start-client) final-ip)
        (catch Exception e
          (println " Failed to start client:" (.getMessage e))
          (println " Make sure Quil or JavaFX are properly configured")
          (System/exit 1))))))

(defn -main
  [& args]
  (println "
     __  ___ ___      ___                      
    /  |/  // _ \\    / _ \\___ ____  ___ ____  
   / /|_/ // ___/   / , _/ _ `/ _ \\/ _ `/ _ \\ 
  /_/  /_//_/      /_/|_|\\_,_/_//_/\\_, /_//_/ 
                                   /___/       
    ")
  (println "MIRE-ARENA - MULTIPLAYER BOSS BATTLE")
  (println "Version: 0.1.0-SNAPSHOT")
  (println "")

  (let [mode (first args)
        arg1 (second args)]

    (cond
      (or (= mode "server") (= mode "1") (= mode "s"))
      (start-server-mode)

      (or (= mode "client") (= mode "2") (= mode "c"))
      (start-client-mode arg1)

      (nil? mode)
      (do
        (println "SELECT MODE:")
        (println "1 - Start Server (Host Game)")
        (println "2 - Start Client (Join Game)")
        (print "Choose mode (1 or 2): ")
        (flush)

        (let [choice (read-line)]
          (case choice
            "1" (start-server-mode)
            "2" (start-client-mode nil)
            (do
              (println "Invalid choice")
              (System/exit 1)))))

      :else
      (do
        (println "Unknown command:" mode)
        (println "")
        (System/exit 1)))))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (println " ")
            (println "  Shutting down Arena...")
            (try
              (server/stop-server)
              (catch Exception e
                (println "  Clean shutdown completed"))))))