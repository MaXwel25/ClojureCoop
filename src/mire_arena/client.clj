(ns mire-arena.client
  (:require [quil.core :as q]
            [quil.middleware :as m]
            [mire-arena.client.websocket :as ws]
            [mire-arena.client.input :as input]
            [mire-arena.client.graphics :as graphics]
            [mire-arena.client.ui :as ui]
            [mire-arena.client.state :as state]
            [mire-arena.shared :as shared])
  (:import [java.lang RuntimeException]))

(defn setup []
  (try
    (q/frame-rate 60)
    (q/color-mode :rgb)
    (q/rect-mode :corner)
    (q/ellipse-mode :center)
    (q/text-font (q/create-font "Arial" 14 true))
    (q/smooth)

    (state/init)

    (println "Connecting to WebSocket...")
    (ws/connect)

    (println "
       __  ___ ___      ___                      
      /  |/  // _ \\    / _ \\___ ____  ___ ____  
     / /|_/ // ___/   / , _/ _ `/ _ \\/ _ `/ _ \\ 
    /_/  /_//_/      /_/|_|\\_,_/_//_/\\_, /_//_/ 
                                     /___/       
      ")
    (println "MIRE-ARENA CLIENT")
    (println " ")
    (println "Game initialized - waiting for connection...")
    (println "Controls: WASD + Mouse | Ctrl+R: Reconnect")

    {:last-update (System/currentTimeMillis)
     :start-time (System/currentTimeMillis)
     :frame-count 0}

    (catch Exception e
      (println "Error during setup:" (.getMessage e))
      (throw e))))

(defn update-game-state [state]
  (try
    (let [current-time (System/currentTimeMillis)
          delta-time (- current-time (:last-update state))]

      ;; обновляем ввод только если подключены
      (when (= (state/get-connection-status) :connected)
        (input/update-input))

      (assoc state
             :last-update current-time
             :frame-count (inc (:frame-count state))
             :delta-time delta-time))

    (catch Exception e
      (println "Warning: Error in update-game-state:" (.getMessage e))
      state)))

(defn draw-state [state]
  (try
    (let [current-time (System/currentTimeMillis)]

      ;; FPS update (safe)
      (state/update-fps current-time)

      ;; Background
      (q/background 30 30 60)

      ;; Arena grid
      (doseq [i (range 0 shared/arena-height 2)]
        (let [alpha (int (* 50 (/ i shared/arena-height)))]
          (q/stroke 60 60 100 alpha)
          (q/line 0 i shared/arena-width i)))

      ;; Game objects (players, boss, bullets, bonuses)
      (graphics/draw-game-objects-with-effects)

      ;; UI layer
      (ui/draw-ui current-time)

      ;; Lightweight debug overlay (safe)
      (when (:show-debug? (state/get-debug-info))
        (let [stats (or (state/get-game-stats) {})
              game-info (or (state/get-comprehensive-game-info) {})]
          (q/fill 255 255 255 200)
          (q/text-size 12)

          (q/text (str "FPS: " (or (:fps stats) 0)) 10 20)

          (q/text (str "Players: "
                       (or (:players-alive game-info) 0)
                       "/"
                       (or (:players-total game-info) 0))
                  10 35)

          (q/text (str "Boss HP: "
                       (or (:boss-hp stats) 0)
                       "/"
                       shared/boss-max-hp)
                  10 50))))
    (catch Exception e
      ;; Never crash render loop
      (println "Warning: Error in draw-state:" (or (.getMessage e) e)))))


(defn key-pressed [state event]
  (try
    (input/handle-key-pressed event)
    state
    (catch Exception e
      (println "Warning: Error in key-pressed:" (.getMessage e))
      state)))

(defn key-released [state event]
  (try
    (input/handle-key-released event)
    state
    (catch Exception e
      (println "Warning: Error in key-released:" (.getMessage e))
      state)))

(defn mouse-pressed [state event]
  (try
    (input/handle-mouse-pressed event)
    state
    (catch Exception e
      (println "Warning: Error in mouse-pressed:" (.getMessage e))
      state)))

(defn mouse-released [state event]
  state)

(defn mouse-dragged [state event]
  (try
    (input/handle-mouse-dragged event)
    state
    (catch Exception e
      (println "Warning: Error in mouse-dragged:" (.getMessage e))
      state)))

(defn mouse-wheel [state event]
  (try
    (input/handle-mouse-wheel event)
    state
    (catch Exception e
      (println "Warning: Error in mouse-wheel:" (.getMessage e))
      state)))

(defn focus-gained [state]
  (try
    (input/handle-focus-gained)
    (println "Window focused - controls active")
    state
    (catch Exception e
      (println "Warning: Error in focus-gained:" (.getMessage e))
      state)))

(defn focus-lost [state]
  (try
    (input/handle-focus-lost)
    (println "Window focus lost - controls disabled")
    state
    (catch Exception e
      (println "Warning: Error in focus-lost:" (.getMessage e))
      state)))

(defn start-sketch []
  (try
    (println "Starting Quil sketch...")
    (q/sketch
     :title "Arena Game - Multiplayer Boss Battle"
     :size [shared/arena-width shared/arena-height]
     :setup setup
     :update update-game-state
     :draw draw-state
     :key-pressed key-pressed
     :key-released key-released
     :mouse-pressed mouse-pressed
     :mouse-released mouse-released
     :mouse-dragged mouse-dragged
     :mouse-wheel mouse-wheel
     :focus-gained focus-gained
     :focus-lost focus-lost
     :features [:keep-on-top :exit-on-close]
     :middleware [m/fun-mode])
    (catch Exception e
      (println "Failed to start Quil sketch:" (.getMessage e))
      (println "Make sure JavaFX is properly installed and configured")
      (throw e))))

(defn start-client [server-ip]
  (println "Starting client with server IP:" server-ip)
  (ws/set-server-url! server-ip)

  (if (or (= server-ip "localhost")
          (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" server-ip))
    (do
      (println "Attempting connection to:" server-ip)
      (start-sketch))
    (do
      (println "Invalid server IP address:" server-ip)
      (println "Use 'localhost' or a valid IP address like '192.168.1.100'")
      (System/exit 1))))

(defn stop-client []
  (println "Stopping Arena client...")
  (ws/disconnect)
  (println "Client stopped"))

(defn restart-client []
  (println "Restarting client...")
  (stop-client)
  (Thread/sleep 1000)
  (ws/reconnect))

(defn get-client-status []
  (let [connection-status (ws/get-connection-status)
        game-info (state/get-comprehensive-game-info)
        state @state/game-state]
    {:connection connection-status
     :game-state {:players (count (:players state))
                  :bullets (count (:bullets state))
                  :bonuses (count (:bonuses state))
                  :self-id (:self-id state)}
     :performance {:fps (:fps (state/get-game-stats))
                   :uptime (- (System/currentTimeMillis) (:start-time state))}
     :boss (:boss game-info)}))

(defn -main []
  (println "Starting Arena Client in standalone mode...")
  (println "Connect to server: localhost:8080")
  (start-client "localhost"))

(defn enable-debug-mode []
  (state/set-debug-info true)
  (println "Debug mode enabled"))

(defn disable-debug-mode []
  (state/set-debug-info false)
  (println "Debug mode disabled"))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. ^Runnable (fn [] (stop-client))))