(ns mire-arena.client.input
  (:require [quil.core :as q]
            [mire-arena.client.websocket :as ws]
            [mire-arena.client.state :as state]
            [mire-arena.shared :as shared]))

(def mouse-shoot-cooldown 300)
(def last-mouse-shot (atom 0))
(def sprint-multiplier 2)

(defn get-player-size [player]
  (if (= (:id player) "boss")
    shared/boss-size
    shared/player-size))

(defn calculate-new-position [player keys]
  (let [x (or (:x player) 400)
        y (or (:y player) 300)
        base-speed (if (contains? keys :shift)
                     (* shared/player-speed sprint-multiplier)
                     shared/player-speed)
        actual-speed (if (:speed-buff player)
                       (* base-speed (get-in player [:speed-buff :value] 1))
                       base-speed)
        player-size (get-player-size player)
        new-x (cond
                (contains? keys :a) (max 0 (- x actual-speed))
                (contains? keys :d) (min (- shared/arena-width player-size) (+ x actual-speed))
                :else x)
        new-y (cond
                (contains? keys :w) (max 0 (- y actual-speed))
                (contains? keys :s) (min (- shared/arena-height player-size) (+ y actual-speed))
                :else y)]
    {:x new-x :y new-y}))

;; ИСПРАВЛЕНА: Убрана лишняя локальная обновление состояния
(defn update-player-position []
  (let [self-id (state/get-self-id)
        players (state/get-players)
        self (get players self-id)]
    ;; Только если у нас есть self-id и игрок жив
    (when (and self-id self (not (:dead self)))
      (let [keys (state/get-keys-pressed)]
        ;; Отправляем движение только если есть нажатые клавиши движения
        (when (or (contains? keys :w) (contains? keys :a)
                  (contains? keys :s) (contains? keys :d))
          (let [new-pos (calculate-new-position self keys)]
            (ws/send-move (:x new-pos) (:y new-pos))))))))

(defn handle-shooting [key]
  (let [self-id (state/get-self-id)
        players (state/get-players)
        self (get players self-id)]
    (when (and self (not (:dead self)))
      (let [dx (case key
                 :up 0
                 :down 0
                 :left -1
                 :right 1
                 0)
            dy (case key
                 :up -1
                 :down 1
                 :left 0
                 :right 0
                 0)]
        (when (or (not= dx 0) (not= dy 0))
          (ws/send-shoot dx dy))))))

(defn handle-mouse-shooting [event]
  (let [self-id (state/get-self-id)
        players (state/get-players)
        self (get players self-id)
        now (System/currentTimeMillis)]
    (when (and self (not (:dead self)) (>= (- now @last-mouse-shot) mouse-shoot-cooldown))
      (let [mx (:x event)
            my (:y event)
            player-x (or (:x self) 400)
            player-y (or (:y self) 300)
            player-size (get-player-size self)
            player-center-x (+ player-x (/ player-size 2))
            player-center-y (+ player-y (/ player-size 2))
            dx (- mx player-center-x)
            dy (- my player-center-y)
            distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
        (when (> distance 10)
          (let [normalized-dx (/ dx distance)
                normalized-dy (/ dy distance)]
            (reset! last-mouse-shot now)
            (ws/send-shoot normalized-dx normalized-dy)))))))

(defn handle-key-pressed [event]
  (let [key-code (q/key-code)
        key-char (q/key-as-keyword)
        raw-key (q/raw-key)]
    (cond
      (#{:w :a :s :d} key-char)
      (do
        (state/add-key-pressed key-char)
        ;; Немедленное обновление позиции при нажатии клавиши
        (update-player-position))

      (#{:up :down :left :right} key-char)
      (handle-shooting key-char)

      (= key-code 16)
      (state/add-key-pressed :shift)

      (and (= key-char :r) (contains? (state/get-keys-pressed) :ctrl))
      (do
        (println "🔄 Manual reconnection triggered")
        (ws/connect))

      (= key-code 32) ; Пробел
      (let [self-id (state/get-self-id)
            players (state/get-players)
            self (get players self-id)]
        (when (and self (not (:dead self)))
          (let [keys-pressed (state/get-keys-pressed)
                dx (cond
                     (contains? keys-pressed :a) -1
                     (contains? keys-pressed :d) 1
                     :else 0)
                dy (cond
                     (contains? keys-pressed :w) -1
                     (contains? keys-pressed :s) 1
                     :else (if (zero? dx) -1 0))]
            (ws/send-shoot dx dy))))

      (= key-code 114) ; F3
      (do
        (println "=== DEBUG INFO ===")
        (let [players (state/get-players)
              self-id (state/get-self-id)
              self (get players self-id)]
          (println "Self ID:" self-id)
          (println "Self player:" self)
          (println "Total players:" (count players))
          (println "Connection:" (state/get-connection-status))
          (println "Keys pressed:" (state/get-keys-pressed))))

      :else
      nil))) ; Не выводим лишние сообщения

(defn handle-key-released [event]
  (let [key-code (q/key-code)
        key-char (q/key-as-keyword)]
    (cond
      (#{:w :a :s :d} key-char)
      (do
        (state/remove-key-pressed key-char)
        ;; Отправляем последнее обновление позиции при отпускании клавиши
        (update-player-position))

      (= key-code 16)
      (state/remove-key-pressed :shift)

      :else nil)))

(defn handle-mouse-pressed [event]
  (let [button (:button event)]
    (case button
      :left (handle-mouse-shooting event)
      :right nil ; Не выводим сообщение
      :center nil ; Не выводим сообщение
      nil)))

(defn handle-mouse-dragged [event]
  (let [button (:button event)]
    (when (= button :left)
      (handle-mouse-shooting event))))

(defn handle-focus-gained []
  (println "✅ Window gained focus")
  (state/set-window-focused true))

(defn handle-focus-lost []
  (println "⚠️ Window lost focus - clearing keys")
  (state/set-window-focused false)
  (state/clear-keys-pressed))

(defn handle-mouse-wheel [event]
  nil) ; Игнорируем колесо мыши

(defn get-mouse-direction [player-x player-y mouse-x mouse-y]
  (let [dx (- mouse-x player-x)
        dy (- mouse-y player-y)
        distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (> distance 0)
      [(/ dx distance) (/ dy distance)]
      [0 -1])))

(defn auto-shoot-enabled? []
  false)

(defn handle-continuous-shooting []
  (when (auto-shoot-enabled?)
    (let [self-id (state/get-self-id)
          players (state/get-players)
          self (get players self-id)
          now (System/currentTimeMillis)]
      (when (and self (not (:dead self)) (>= (- now @last-mouse-shot) mouse-shoot-cooldown))
        (let [mx (q/mouse-x)
              my (q/mouse-y)
              player-x (or (:x self) 400)
              player-y (or (:y self) 300)
              player-size (get-player-size self)
              [dx dy] (get-mouse-direction
                       (+ player-x (/ player-size 2))
                       (+ player-y (/ player-size 2))
                       mx my)]
          (when (and (not (zero? dx)) (not (zero? dy)))
            (reset! last-mouse-shot now)
            (ws/send-shoot dx dy)))))))

(defn update-input []
  ;; Обновляем позицию только если есть нажатые клавиши
  (let [keys-pressed (state/get-keys-pressed)
        movement-keys #{:w :a :s :d}]
    (when (some movement-keys keys-pressed)
      (update-player-position)))
  (handle-continuous-shooting))