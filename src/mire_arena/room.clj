(ns mire-arena.room
  (:require [mire-arena.shared :as shared]
            [clojure.set :as set]))

;; Состояние комнаты
(defonce room-state (atom {:id "main-room"
                          :players {}
                          :bonuses {}
                          :messages []
                          :walls shared/walls
                          :door shared/door
                          :next-player-id 0
                          :next-bonus-id 0
                          :created-at (System/currentTimeMillis)}))

;; Генерация бонусов в комнате
(defn generate-bonuses [count]
  (into {}
    (map-indexed
      (fn [i _]
        (let [bonus-type (rand-nth shared/bonus-types)]
          [(str "bonus-" i)
           {:id (str "bonus-" i)
            :type (:type bonus-type)
            :color (:color bonus-type)
            :effect (:effect bonus-type)
            :x (-> (shared/random-bonus-position) :x)
            :y (-> (shared/random-bonus-position) :y)
            :collected? false
            :created-at (System/currentTimeMillis)}]))
      (range count))))

;; Инициализация комнаты
(defn init-room []
  (reset! room-state
    {:id "main-room"
     :players {}
     :bonuses (generate-bonuses 8) ; 8 бонусов в комнате
     :messages []
     :walls shared/walls
     :door shared/door
     :next-player-id 0
     :next-bonus-id 8
     :created-at (System/currentTimeMillis)})
  (println "Комната инициализирована с 8 бонусами"))

;; Получение состояния комнаты
(defn get-room-state []
  @room-state)

;; Добавление игрока
(defn add-player [player-id nickname avatar color]
  (let [spawn-pos (shared/get-available-spawn)
        player {:id player-id
                :nickname nickname
                :avatar avatar
                :color color
                :x (:x spawn-pos)
                :y (:y spawn-pos)
                :target-x (:x spawn-pos)
                :target-y (:y spawn-pos)
                :bonuses []
                :joined-at (System/currentTimeMillis)}]
    
    (swap! room-state update :players assoc player-id player)
    player))

;; Удаление игрока
(defn remove-player [player-id]
  (swap! room-state update :players dissoc player-id))

;; Обновление позиции игрока
(defn update-player-position [player-id x y]
  (swap! room-state assoc-in [:players player-id :x] x)
  (swap! room-state assoc-in [:players player-id :y] y)
  (swap! room-state assoc-in [:players player-id :target-x] x)
  (swap! room-state assoc-in [:players player-id :target-y] y))

;; Обновление цели движения
(defn update-player-target [player-id target-x target-y]
  (swap! room-state assoc-in [:players player-id :target-x] target-x)
  (swap! room-state assoc-in [:players player-id :target-y] target-y))

;; Сбор бонуса
(defn collect-bonus [player-id bonus-id]
  (when-let [bonus (get-in @room-state [:bonuses bonus-id])]
    (when (not (:collected? bonus))
      ;; Помечаем бонус как собранный
      (swap! room-state assoc-in [:bonuses bonus-id :collected?] true)
      
      ;; Добавляем бонус игроку
      (swap! room-state update-in [:players player-id :bonuses] conj
        {:type (:type bonus)
         :color (:color bonus)
         :effect (:effect bonus)
         :collected-at (System/currentTimeMillis)})
      
      ;; Создаем новый бонус
      (let [new-bonus-id (str "bonus-" (:next-bonus-id @room-state))
            bonus-type (rand-nth shared/bonus-types)
            new-bonus {:id new-bonus-id
                       :type (:type bonus-type)
                       :color (:color bonus-type)
                       :effect (:effect bonus-type)
                       :x (-> (shared/random-bonus-position) :x)
                       :y (-> (shared/random-bonus-position) :y)
                       :collected? false
                       :created-at (System/currentTimeMillis)}]
        
        (swap! room-state update :bonuses assoc new-bonus-id new-bonus)
        (swap! room-state update :next-bonus-id inc))
      
      true)))

;; Проверка коллизий со стенами
(defn check-wall-collision [x y]
  (some (fn [wall]
          (shared/point-in-rect?
            x y
            (:x wall) (:y wall)
            (:width wall) (:height wall)))
        shared/walls))

;; Проверка выхода через дверь
(defn check-door-collision [x y]
  (shared/point-in-rect?
    x y
    (:x shared/door) (:y shared/door)
    (:width shared/door) (:height shared/door)))

;; Получение ближайшего игрока
(defn get-nearest-player [player-id]
  (let [players (:players @room-state)
        current-player (get players player-id)]
    
    (when current-player
      (->> players
           (remove (fn [[id _]] (= id player-id)))
           (map (fn [[id player]]
                  [id player (shared/distance (:x current-player) (:y current-player)
                                             (:x player) (:y player))]))
           (sort-by last)
           first))))

;; Очистка старых сообщений
(defn cleanup-old-messages []
  (let [now (System/currentTimeMillis)]
    (swap! room-state update :messages
      (fn [messages]
        (filter #(or (not= (:status %) :delivered)
                     (< (- now (:delivered-at %)) shared/message-display-time))
                messages)))))

;; Получение статистики комнаты
(defn get-room-stats []
  (let [state @room-state]
    {:players-count (count (:players state))
     :bonuses-count (count (filter #(not (:collected? %)) (vals (:bonuses state))))
     :messages-count (count (:messages state))
     :uptime (- (System/currentTimeMillis) (:created-at state))}))
