(ns mire-arena.server
  (:require [org.httpkit.server :as ws]
            [cheshire.core :as json]
            [mire-arena.shared :refer :all]
            [mire-arena.bot :as bot]
            [mire-arena.network-utils :as net])
  (:import [java.util UUID]))

;; глобальное состояние
(defonce clients (atom {}))
(defonce game-state (atom initial-state))
(defonce game-running? (atom true))
(defonce game-thread (atom nil))
(defonce last-bonus-spawn (atom (System/currentTimeMillis)))
(defonce server-instance (atom nil))
(defonce boss-ai-running? (atom false))
(defonce boss-ai-thread (atom nil))
(defonce server-stats (atom {:start-time (System/currentTimeMillis)
                             :players-connected 0
                             :players-disconnected 0
                             :bullets-fired 0
                             :bonuses-spawned 0}))

;; утилиты для работы с WebSocket
(defn send-json! [ch data]
  (try
    (when (ws/open? ch)
      (ws/send! ch (json/generate-string data)))
    (catch Exception e
      (println " Error sending to client:" e))))

(defn broadcast [data]
  (let [msg (json/generate-string data)]
    (doseq [[_ client] @clients]
      (try
        (when (ws/open? (:channel client))
          (ws/send! (:channel client) msg))
        (catch Exception e
          (println " Error broadcasting to client:" e))))))

(defn broadcast-except [except-pid data]
  (let [msg (json/generate-string data)]
    (doseq [[pid client] @clients]
      (when (and (not= pid except-pid) (ws/open? (:channel client)))
        (try
          (ws/send! (:channel client) msg)
          (catch Exception e
            (println " Error broadcasting to client" pid ":" e)))))))

;; обработка сообщений от клиентов
(defn handle-move [pid data]
  (when (and data (valid-coordinates? (:x data) (:y data)))
    (swap! game-state update-in [:players pid]
           #(when % (assoc % :x (:x data) :y (:y data))))))

(defn handle-shoot [pid data]
  (let [now (System/currentTimeMillis)
        player (get-in @game-state [:players pid])]
    (when (and player (not (:dead player))
               (>= (- now (get player :last-shot 0)) 300))
      (let [bullet-id (str (:next-bullet-id @game-state))
            bullet-x (+ (:x player) (/ player-size 2))
            bullet-y (+ (:y player) (/ player-size 2))
            bullet {:id bullet-id
                    :x bullet-x :y bullet-y
                    :dx (:dx data) :dy (:dy data)
                    :owner pid
                    :created-at now}]
        (swap! game-state
               (fn [state]
                 (-> state
                     (assoc-in [:bullets bullet-id] bullet)
                     (update :next-bullet-id inc)
                     (assoc-in [:players pid :last-shot] now))))
        (swap! server-stats update :bullets-fired inc)
        (println "  Player" pid " shot bullet" bullet-id "at" bullet-x bullet-y)))))

(defn handle-ping [pid data]
  (swap! clients update-in [pid :last-ping] (constantly (System/currentTimeMillis)))
  (when-let [client (get @clients pid)]
    (send-json! (:channel client) {:type "pong" :timestamp (:timestamp data)})))

(defn handle-message [pid msg]
  (try
    (when (and msg (string? msg) (not (empty? msg)))
      (let [data (json/parse-string msg true)
            msg-type (:type data)]
        (case msg-type
          "move" (handle-move pid data)
          "shoot" (handle-shoot pid data)
          "ping" (handle-ping pid data)
          (println "⚠️ Unknown message type from" pid ":" msg-type))))
    (catch Exception e
      (println "  Error handling message from" pid ":" e))))

;; игровая логика с поддержкой босса (на Prolog)
(defn update-bullets []
  (swap! game-state
         (fn [state]
           (let [now (System/currentTimeMillis)
                 bullets (:bullets state)
                 updated-bullets (->> bullets
                                      (map (fn [[id bullet]]
                                             [id (-> bullet
                                                     (update :x + (* bullet-speed (:dx bullet)))
                                                     (update :y + (* bullet-speed (:dy bullet))))]))
                                      (filter (fn [[id bullet]]
                                                (and (>= (:x bullet) 0) (<= (:x bullet) arena-width)
                                                     (>= (:y bullet) 0) (<= (:y bullet) arena-height)
                                                     (< (- now (:created-at bullet)) bullet-lifetime))))
                                      (into {}))]
             (assoc state :bullets updated-bullets)))))

(defn calculate-damage [attacker-id target-id]
  (let [attacker (get-in @game-state [:players attacker-id])
        base-damage (if (= attacker-id "boss") boss-damage bullet-damage)]
    (if attacker
      (let [damage-buff (when (:damage-buff attacker) (:value (:damage-buff attacker)))]
        (if damage-buff
          (+ base-damage damage-buff)
          base-damage))
      base-damage)))

(defn check-bullet-collisions []
  (let [state @game-state
        bullets (:bullets state)
        players (:players state)
        collisions (atom {})]
    (doseq [[bullet-id bullet] bullets
            :let [owner (:owner bullet)
                  bullet-x (:x bullet)
                  bullet-y (:y bullet)]]
      (doseq [[player-id player] players
              :when (and (not= player-id owner)
                         (not (:dead player))
                         (collides? bullet-x bullet-y bullet-size
                                    (:x player) (:y player)
                                    (if (= player-id "boss") boss-size player-size)))]
        (swap! collisions update bullet-id (fnil conj #{}) player-id)))

    (when (seq @collisions)
      (swap! game-state
             (fn [state]
               (reduce (fn [s [bullet-id player-ids]]
                         (reduce (fn [s* player-id]
                                   (let [damage (calculate-damage (get-in s* [:bullets bullet-id :owner]) player-id)
                                         current-hp (get-in s* [:players player-id :hp]
                                                            (if (= player-id "boss") 1000 max-hp))
                                         new-hp (- current-hp damage)
                                         dead? (<= new-hp 0)]
                                     (println "  Bullet" bullet-id "from" (or (get-in s* [:bullets bullet-id :owner]) "unknown")
                                              "hit" (if (= player-id "boss") "BOSS" (str "player " player-id))
                                              "HP:" current-hp "->" new-hp)
                                     (-> s*
                                         (assoc-in [:players player-id :hp] (max 0 new-hp))
                                         (cond->
                                          (get-in s* [:bullets bullet-id :owner]) ; Только если owner не nil
                                           (update-in [:players (get-in s* [:bullets bullet-id :owner]) :score] (fnil inc 0)))
                                         (assoc-in [:players player-id :dead] dead?)
                                         (assoc-in [:players player-id :dead-since]
                                                   (when dead? (System/currentTimeMillis))))))
                                 s
                                 player-ids))
                       (update state :bullets #(apply dissoc % (keys @collisions)))
                       @collisions))))))

(defn spawn-bonus []
  (let [bonus-id (str (:next-bonus-id @game-state))
        bonus-types [{:type :health :color [0 255 0] :value 50}
                     {:type :speed :color [0 0 255] :value 2}
                     {:type :damage :color [255 0 0] :value 10}]
        bonus-type (rand-nth bonus-types)
        bonus (merge {:id bonus-id
                      :x (rand-int (- arena-width bonus-size))
                      :y (rand-int (- arena-height bonus-size))
                      :created-at (System/currentTimeMillis)}
                     bonus-type)]
    (swap! game-state
           (fn [state]
             (-> state
                 (assoc-in [:bonuses bonus-id] bonus)
                 (update :next-bonus-id inc))))
    (swap! server-stats update :bonuses-spawned inc)
    (println " + Spawned bonus:" (:type bonus-type) "at" (:x bonus) (:y bonus))))

(defn update-bonuses []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (let [bonuses (:bonuses state)
                   updated-bonuses (->> bonuses
                                        (filter (fn [[id bonus]]
                                                  (< (- now (:created-at bonus)) bonus-lifetime)))
                                        (into {}))]
               (assoc state :bonuses updated-bonuses))))))

(defn check-bonus-collisions []
  (let [state @game-state
        bonuses (:bonuses state)
        players (:players state)
        collected (atom {})]
    (doseq [[bonus-id bonus] bonuses
            :let [bonus-x (:x bonus)
                  bonus-y (:y bonus)]]
      (doseq [[player-id player] players
              :when (and (not (:dead player))
                         ;; проверяем, что координаты бонуса и игрока не nil (на всякий)
                         bonus-x bonus-y (:x player) (:y player)
                         (collides? bonus-x bonus-y bonus-size
                                    (:x player) (:y player)
                                    (if (= player-id "boss") boss-size player-size)))]
        (swap! collected assoc bonus-id player-id)))

    (when (seq @collected)
      (swap! game-state
             (fn [state]
               (reduce (fn [s [bonus-id player-id]]
                         (let [bonus (get-in s [:bonuses bonus-id])
                               bonus-type (:type bonus)]
                           (println " ! Player" player-id "collected bonus:" bonus-type)
                           (-> s
                               (update :bonuses dissoc bonus-id)
                               (update-in [:players player-id :score] (fnil + 0) 10)
                               (cond->
                                (= bonus-type :health)
                                 (update-in [:players player-id :hp]
                                            (fn [hp]
                                              (let [max-hp (if (= player-id "boss") 1000 max-hp)]
                                                (min max-hp (+ (or hp max-hp) (:value bonus))))))

                                 (= bonus-type :speed)
                                 (assoc-in [:players player-id :speed-buff]
                                           {:value (:value bonus) :expires (+ (System/currentTimeMillis) 10000)})

                                 (= bonus-type :damage)
                                 (assoc-in [:players player-id :damage-buff]
                                           {:value (:value bonus) :expires (+ (System/currentTimeMillis) 10000)})))))
                       state
                       @collected))))))

(defn update-player-buffs []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (reduce (fn [s [player-id player]]
                       (cond-> s
                         (and (:speed-buff player) (> now (:expires (:speed-buff player))))
                         (update-in [:players player-id] dissoc :speed-buff)

                         (and (:damage-buff player) (> now (:expires (:damage-buff player))))
                         (update-in [:players player-id] dissoc :damage-buff)))
                     state
                     (:players state))))))

(defn respawn-dead-players []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (reduce (fn [s [player-id player]]
                       (if (and (:dead player)
                                (not= player-id "boss")
                                (> (- now (:dead-since player)) respawn-time))
                         (do
                           (println " Respawning player" player-id)
                           (-> s
                               (assoc-in [:players player-id]
                                         (merge (random-spawn-position)
                                                {:hp max-hp :score (get player :score 0) :dead false}))))
                         s))
                     state
                     (:players state))))))

(defn respawn-boss-if-needed []
  (let [now (System/currentTimeMillis)
        boss (get-in @game-state [:players "boss"])]
    (when (and boss (:dead boss) (> (- now (:dead-since boss)) boss-respawn-time))
      (println " !!! BOSS RESPAWNING!")
      (swap! game-state assoc-in [:players "boss"] (bot/create-boss)))))

(defn cleanup-disconnected-clients []
  (let [now (System/currentTimeMillis)
        timeout 30000
        to-remove (atom [])]
    (doseq [[pid client] @clients
            :when (> (- now (:last-ping client)) timeout)]
      (println "  Removing disconnected client:" pid)
      (swap! to-remove conj pid))

    (when (seq @to-remove)
      (swap! clients #(apply dissoc % @to-remove))
      (swap! game-state update :players #(apply dissoc % @to-remove))
      (swap! server-stats update :players-disconnected + (count @to-remove)))))

(defn spawn-bonus-if-needed []
  (let [now (System/currentTimeMillis)
        state @game-state
        bonuses-count (count (:bonuses state))]
    (when (and (< bonuses-count 3)
               (> (- now @last-bonus-spawn) bonus-spawn-time))
      (reset! last-bonus-spawn now)
      (spawn-bonus))))

;; цикл AI босса
(defn start-boss-ai-loop []
  (println " Starting Boss AI loop...")
  (reset! boss-ai-running? true)
  (reset! boss-ai-thread
          (future
            (while @boss-ai-running?
              (try
                (Thread/sleep boss-ai-tick-ms) ; УЖЕ 500 мс
                (let [before (get-in @game-state [:players "boss"])]
                  ;; проверка состояния боса (жив)
                  (when (and before (not (:dead before)))
                    (swap! game-state bot/update-boss)
                    (let [after (get-in @game-state [:players "boss"])]
                      (when (and after (not= (:x before) (:x after)))
                        (println " Boss moved from" (:x before) "to" (:x after))))))
                (catch Exception e
                  (println " Boss AI error:" (.getMessage e))))))))

(defn stop-boss-ai-loop []
  (reset! boss-ai-running? false)
  (when-let [thread @boss-ai-thread]
    (future-cancel thread)))

(defn game-tick []
  (try
    (update-bullets)
    (update-bonuses)
    (update-player-buffs)
    (check-bullet-collisions)
    (check-bonus-collisions)
    (respawn-dead-players)
    (respawn-boss-if-needed)
    (cleanup-disconnected-clients)
    (spawn-bonus-if-needed)

    ;; обновляем AI босса
    ;; (swap! game-state bot/update-boss)

    (let [current-state @game-state
          game-data {:type "state"
                     :players (:players current-state)
                     :bullets (vals (:bullets current-state))
                     :bonuses (vals (:bonuses current-state))}]
      (broadcast game-data))

    ;; отлавливаем все исключения, чтобы сервер не падал
    (catch Exception e
      (println " Error in game tick, but continuing:" (.getMessage e))
      ;; пытаемся отправить состояние, даже если есть ошибки
      (try
        (let [current-state @game-state
              game-data {:type "state"
                         :players (:players current-state)
                         :bullets (vals (:bullets current-state))
                         :bonuses (vals (:bonuses current-state))}]
          (broadcast game-data))
        (catch Exception e2
          (println "  Could not broadcast state:" (.getMessage e2)))))))

;; игровой цикл
(defn start-game-loop []
  (reset! game-thread
          (future
            (while @game-running?
              (Thread/sleep game-tick-ms)
              (game-tick)))))

(defn stop-game-loop []
  (reset! game-running? false)
  (when-let [thread @game-thread]
    (future-cancel thread)))

(defn stop-server []
  (println "  Stopping Arena server...")
  (stop-game-loop)
  (println "  Game loop stopped"))


;; WebSocket обработчик
(defn ws-handler [req]
  (ws/with-channel req ch
    (let [pid (str (UUID/randomUUID))
          initial-player (merge (random-spawn-position)
                                {:hp max-hp :score 0 :dead false})]

      (println " New player connected:" pid)
      (swap! clients assoc pid {:channel ch :last-ping (System/currentTimeMillis)})
      (swap! game-state assoc-in [:players pid] initial-player)
      (swap! server-stats update :players-connected inc)

      (send-json! ch {:type "init"
                      :self-id pid
                      :players (:players @game-state)})

      (broadcast-except pid {:type "player-joined"
                             :player-id pid
                             :player initial-player})

      (when-let [boss (get-in @game-state [:players "boss"])]
        (send-json! ch {:type "boss-info"
                        :boss boss}))

      (ws/on-receive ch (partial handle-message pid))

      (ws/on-close ch
                   (fn [status]
                     (println " Client disconnected:" pid)
                     (swap! clients dissoc pid)
                     (swap! game-state update :players dissoc pid)
                     (broadcast {:type "player-left" :player-id pid}))))))

;; статистика сервера
(defn get-server-stats []
  (let [stats @server-stats
        uptime (- (System/currentTimeMillis) (:start-time stats))
        current-state @game-state]
    (merge stats
           {:uptime-ms uptime
            :uptime-str (str (int (/ uptime 1000)) "s")
            :current-players (count @clients)
            :total-players (count (:players current-state))
            :active-bullets (count (:bullets current-state))
            :active-bonuses (count (:bonuses current-state))
            :boss-alive? (let [boss (get-in current-state [:players "boss"])]
                           (and boss (not (:dead boss))))})))

;; вывод статистики по серверу
(defn print-server-stats []
  (let [stats (get-server-stats)]
    (println "\n SERVER STATISTICS:")
    (println " ")
    (println "Uptime:" (:uptime-str stats))
    (println "Players connected:" (:current-players stats) "/" (:players-connected stats) "total")
    (println "Players disconnected:" (:players-disconnected stats))
    (println "Bullets fired:" (:bullets-fired stats))
    (println "Bonuses spawned:" (:bonuses-spawned stats))
    (println "Boss alive:" (:boss-alive? stats))
    (println "Active bullets:" (:active-bullets stats))
    (println "Active bonuses:" (:active-bonuses stats))))

;; управление сервером
(defn start-server []
  (println " Starting Arena server on port 8080...")
  (println " Initializing Prolog AI boss...")

  ;; проверяем, доступен ли SWI-Prolog
  (let [{:keys [exit]} (clojure.java.shell/sh "swipl" "--version")]
    (if (zero? exit)
      (println " SWI-Prolog detected")
      (do
        (println " WARNING: SWI-Prolog (swipl) not found!")
        (println "  Boss will still run, but Prolog AI may not work correctly")
        (println " To fix: Install SWI-Prolog and ensure 'swipl' is in PATH"))))

  ;; проверяем наличие Prolog-файла
  (if (.exists (java.io.File. "resources/arena_bot.pl"))
    (println " Prolog logic file found: resources/arena_bot.pl")
    (do
      (println "  WARNING: arena_bot.pl not found!")
      (println "  Boss will use fallback behavior")
      (println "  To fix: Put arena_bot.pl into resources/ folder")))

  ;; инициализация состояния сервера
  (reset! game-running? true)
  (reset! boss-ai-running? false)
  (reset! game-state
          (-> initial-state
              (assoc-in [:players "boss"] (bot/create-boss))))
  (reset! clients {})
  (reset! server-stats
          {:start-time (System/currentTimeMillis)
           :players-connected 0
           :players-disconnected 0
           :bullets-fired 0
           :bonuses-spawned 0})

  ;; запуск логики
  (start-game-loop)
  (start-boss-ai-loop)

  ;; WebSocket сервер
  (reset! server-instance
          (ws/run-server ws-handler {:port 8080}))

  (println "  Server started successfully!")
  (println "  Boss spawned with 1000 HP and Prolog-based AI")
  (println "  Players can connect to: localhost:8080")
  (println "  Or use your local IP:" (net/get-local-ip))
  (println "  Press Ctrl+C to stop the server")

  ;; периодический вывод статистики
  (future
    (while @game-running?
      (Thread/sleep 30000)
      (print-server-stats))))
