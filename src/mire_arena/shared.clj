(ns mire-arena.shared)

;; Константы комнаты (одна комната)
(def room-width 800)
(def room-height 600)
(def wall-thickness 20)
(def door-width 60)
(def door-height 80)

;; Константы игрока
(def player-size 30)
(def player-speed 3)

;; Спавн позиции
(def spawn-positions 
  [{:x 100 :y 100}
   {:x 200 :y 200}
   {:x 300 :y 300}
   {:x 400 :y 100}
   {:x 500 :y 200}])

;; Константы бонусов
(def bonus-size 20)
(def bonus-collect-distance 40)

;; Типы бонусов
(def bonus-types
  [{:type "red" :color [255 0 0] :effect :text-red}
   {:type "blue" :color [0 0 255] :effect :text-blue}
   {:type "green" :color [0 255 0] :effect :text-green}
   {:type "purple" :color [128 0 128] :effect :text-purple}
   {:type "gold" :color [255 215 0] :effect :text-gold}])

;; Константы чата
(def message-speed 5)
(def message-delivery-distance 150)
(def max-message-length 100)
(def message-display-time 5000)

;; Фиксированные стены
(def walls
  [{:x 0 :y 0 :width room-width :height wall-thickness}
   {:x 0 :y 0 :width wall-thickness :height room-height}
   {:x (- room-width wall-thickness) :y 0 :width wall-thickness :height room-height}
   {:x 0 :y (- room-height wall-thickness) :width room-width :height wall-thickness}
   
   ;; Внутренние стены
   {:x 150 :y 100 :width 20 :height 200}
   {:x 300 :y 200 :width 200 :height 20}
   {:x 500 :y 150 :width 20 :height 150}
   {:x 200 :y 400 :width 300 :height 20}
   {:x 600 :y 300 :width 20 :height 150}
   {:x 100 :y 300 :width 150 :height 20}])

;; Дверь
(def door
  {:x 700 :y 260 :width door-width :height door-height})

;; Сетевые константы
(def server-port 8080)
(def tick-rate 60)
(def ping-interval 1000)

;; Цвета
(def colors
  {:background [40 40 50]
   :wall [70 70 90]
   :door [160 120 80]
   :player-default [0 180 255]
   :text [255 255 255]
   :message-bubble [255 255 220 230]
   :selected [255 255 0]})

;; Вспомогательные функции
(defn get-available-spawn []
  (first spawn-positions))

(defn random-bonus-position []
  {:x (+ wall-thickness (rand-int (- room-width (* 2 wall-thickness) bonus-size)))
   :y (+ wall-thickness (rand-int (- room-height (* 2 wall-thickness) bonus-size)))})

(defn distance [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) 
                (Math/pow (- y2 y1) 2))))

(defn point-in-rect? [px py x y w h]
  (and (>= px x) (<= px (+ x w))
       (>= py y) (<= py (+ y h))))

(defn normalize [dx dy]
  (let [magnitude (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? magnitude)
      [0 0]
      [(/ dx magnitude) (/ dy magnitude)])))
