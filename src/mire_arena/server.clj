(ns mire-arena.server
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [mire-arena.shared :as shared]
            [clojure.core.async :as async])
  (:import [java.util UUID]))

(defonce server (atom nil))
(defonce clients (atom {}))
(defonce room-state (atom {:players {}
                          :bonuses {}
                          :messages []
                          :next-bonus-id 0
                          :created-at (System/currentTimeMillis)}))

(defonce message-channel (async/chan 100))

;; Инициализация комнаты
(defn init-room []
  (println "Инициализация комнаты...")
  (let [bonuses (into {}
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
                    (range 8)))]
    
    (reset! room-state
      {:players {}
       :bonuses bonuses
       :messages []
       :walls shared/walls
       :door shared/door
       :next-bonus-id 8
       :created-at (System/currentTimeMillis)})
    
    (println "✅ Комната инициализирована с 8 бонусами")))

;; Рассылка сообщения всем клиентам
(defn broadcast [data]
  (let [msg (json/generate-string data)]
    (doseq [[_ client] @clients]
      (try
        (when (http/open? (:channel client))
          (http/send! (:channel client) msg))
        (catch Exception e
          (println "❌ Ошибка отправки:" e))))))

;; Обработка входа игрока
(defn handle-join [client-id channel data]
  (let [nickname (:nickname data)
        avatar (:avatar data "default")
        color (:color data [0 180 255])
        spawn-pos (shared/get-available-spawn)]

    (println "🎮 Новый игрок:" nickname)
    (println "📤 Отправляю welcome клиенту:" client-id)

    (let [player {:id client-id
                  :nickname nickname
                  :avatar avatar
                  :color color
                  :x (:x spawn-pos)
                  :y (:y spawn-pos)
                  :bonuses []
                  :active-bonuses []
                  :joined-at (System/currentTimeMillis)}]

      (swap! clients assoc client-id {:channel channel :player-id client-id})
      (swap! room-state assoc-in [:players client-id] player)

      ;; Отправляем welcome
      (let [welcome-msg {:type "welcome"
                         :player-id client-id
                         :room @room-state}
            json-msg (json/generate-string welcome-msg)]

        (println "📨 Welcome сообщение структура:" welcome-msg)
        (println "📨 Welcome JSON:" json-msg)

        (http/send! channel json-msg))

      ;; Рассылаем остальным игрокам
      (broadcast
       {:type "player-joined"
        :player player}))))

;; Обработка движения
(defn handle-move [client-id data]
  (let [dx (:dx data)
        dy (:dy data)
        player (get-in @room-state [:players client-id])]
    
    (when player
      (let [new-x (+ (:x player) (* dx shared/player-speed))
            new-y (+ (:y player) (* dy shared/player-speed))
            
            ;; Проверка границ
            bounded-x (max shared/wall-thickness 
                          (min new-x 
                               (- shared/room-width shared/wall-thickness shared/player-size)))
            bounded-y (max shared/wall-thickness 
                          (min new-y 
                               (- shared/room-height shared/wall-thickness shared/player-size)))
            
            ;; Проверка коллизий со стенами
            collides? (some (fn [wall]
                              (shared/point-in-rect?
                                bounded-x bounded-y
                                (:x wall) (:y wall)
                                (:width wall) (:height wall)))
                            shared/walls)
            
            final-x (if collides? (:x player) bounded-x)
            final-y (if collides? (:y player) bounded-y)]
        
        (swap! room-state assoc-in [:players client-id :x] final-x)
        (swap! room-state assoc-in [:players client-id :y] final-y)
        
        (broadcast
          {:type "player-moved"
           :player-id client-id
           :x final-x :y final-y})))))

;; Обработка отправки сообщения
(defn handle-send-message [client-id data]
  (let [receiver-id (:receiver data)
        text (subs (:text data) 0
                   (min shared/max-message-length (count (:text data))))
        
        message-id (str (UUID/randomUUID))
        message {:id message-id
                 :sender client-id
                 :receiver receiver-id
                 :text text
                 :timestamp (System/currentTimeMillis)
                 :status :sending
                 :start-time (System/currentTimeMillis)}]
    
    (swap! room-state update :messages conj message)
    
    (async/go
      (async/<! (async/timeout 1000))
      
      (let [room @room-state
            sender (get-in room [:players client-id])
            receiver (get-in room [:players receiver-id])]
        
        (when (and sender receiver)
          (let [distance (shared/distance (:x sender) (:y sender) 
                                         (:x receiver) (:y receiver))]
            
            (if (< distance shared/message-delivery-distance)
              (do
                (swap! room-state update :messages
                  (fn [messages]
                    (map #(if (= (:id %) message-id)
                            (assoc % :status :delivered 
                                   :delivered-at (System/currentTimeMillis))
                            %)
                         messages)))
                
                (broadcast
                  {:type "message-delivered"
                   :message-id message-id})
                
                (println "✉ Сообщение доставлено от" (:nickname sender) "к" (:nickname receiver)))
              
              (do
                (swap! room-state update :messages
                  (fn [messages]
                    (remove #(= (:id %) message-id) messages)))
                
                (broadcast
                  {:type "message-failed"
                   :message-id message-id
                   :reason "Получатель слишком далеко"})))))))
    
    (broadcast
      {:type "message-sent"
       :message message})))

;; Обработка выхода игрока
(defn handle-exit [client-id]
  (println "👋 Игрок вышел:" client-id)
  (swap! room-state update :players dissoc client-id)
  (swap! clients dissoc client-id)
  
  (broadcast
    {:type "player-left"
     :player-id client-id}))

;; WebSocket обработчик
(defn ws-handler [request]
  (http/with-channel request channel
    (http/on-receive channel
      (fn [data]
        (try
          (let [msg (json/parse-string data true)
                client-id (:client-id msg)
                msg-type (:type msg)]
            
            ;; Логируем только если это не ping сообщение
            (when (not= msg-type "ping")
              (println "📨 Получено сообщение от клиента:" data)
              (println "📊 Распарсенные данные:" msg)
              (println "🎯 Тип сообщения:" msg-type))
            
            (case msg-type
              "join" (handle-join client-id channel msg)
              "move" (handle-move client-id msg)
              "send-message" (handle-send-message client-id msg)
              "exit" (handle-exit client-id)
              "ping" (do
                       ;; Не логируем ping, просто отправляем pong
                       (http/send! channel "pong"))
              (println "⚠ Неизвестный тип сообщения:" msg-type)))
          
          (catch Exception e
            ;; Игнорируем ошибки парсинга пустых сообщений
            (when (not= data "ping")
              (println "❌ Ошибка обработки сообщения:" e)
              (println "📨 Полученное сообщение было:" data))))))
    
    (http/on-close channel
      (fn [status]
        (let [client-id (some (fn [[id client]] 
                                (when (= (:channel client) channel) id)) 
                              @clients)]
          (when client-id
            (handle-exit client-id)))))))

;; Запуск сервера
(defn start []
  (println "🚀 Запуск Mire Arena сервера на порту 8080")
  (init-room)
  
  ;; Очистка старых сообщений каждые 5 секунд
  (future
    (loop []
      (Thread/sleep 5000)
      (let [now (System/currentTimeMillis)]
        (swap! room-state update :messages
          (fn [messages]
            (filter #(or (not= (:status %) :delivered)
                         (< (- now (:delivered-at %)) shared/message-display-time))
                    messages))))
      (recur)))
  
  (reset! server (http/run-server ws-handler {:port shared/server-port}))
  (println "✅ Сервер запущен! Ожидаем подключений..."))

;; Остановка сервера
(defn stop []
  (when @server
    (@server)
    (reset! server nil)
    (println "🛑 Сервер остановлен")))
