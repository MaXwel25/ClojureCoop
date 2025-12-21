(ns mire-arena.client.graphics
  (:require [quil.core :as q]
            [mire-arena.client.state :as state]
            [mire-arena.shared :as shared]))

(defn safe-get-number [obj & path]
  (let [val (get-in obj path)]
    (when (number? val) val)))

(defn safe-player-position [player]
  (let [pos (:position player)]
    (if (and pos (map? pos))
      {:x (:x pos) :y (:y pos)}
      (if (and (contains? player :x) (contains? player :y))
        {:x (:x player) :y (:y player)}
        {:x 0 :y 0}))))

(defn draw-player [player self? boss?]
  (when (and player (map? player))
    ;; получаем координаты напрямую из игрока или из вложенной позиции
    (let [x (or (:x player) (get-in player [:position :x]) 400)
          y (or (:y player) (get-in player [:position :y]) 300)
          hp (:hp player)
          max-hp (if boss? shared/boss-max-hp shared/max-hp)
          dead? (:dead player)
          score (:score player 0)
          speed-buff? (:speed-buff player)
          damage-buff? (:damage-buff player)
          size (if boss? shared/boss-size shared/player-size)]

      ;; проверяем что координаты - числа
      (when (and (number? x) (number? y))
        (let [color (cond
                      dead? [100 100 100 150]
                      self? [0 255 0]
                      boss? [128 0 128]
                      :else [255 0 0])
              hp-bar-width (if boss? 100 shared/player-size)
              hp-bar-height (if boss? 8 5)
              hp-bar-y (if boss? (- y 25) (- y 15))
              text-y (if boss? (- y 35) (- y 20))
              center-x (+ x (/ size 2))
              center-y (+ y (/ size 2))]

          ;; рисуем тело игрока
          (apply q/fill color)
          (q/rect x y size size)

          ;; баффы
          (when speed-buff?
            (q/fill 0 0 255 100)
            (q/ellipse center-x center-y (+ size 8) (+ size 8)))

          (when damage-buff?
            (q/fill 255 165 0 100)
            (q/rect (- x 4) (- y 4) (+ size 8) (+ size 8)))

          ;; полоса HP
          (when (and (not dead?) (number? hp) (number? max-hp) (pos? max-hp))
            (let [hp-percent (/ (or hp 0) max-hp)
                  hp-width (* hp-bar-width hp-percent)
                  hp-color (cond
                             (< hp-percent 0.2) [255 0 0]
                             (< hp-percent 0.5) [255 165 0]
                             :else [0 255 0])]

              (q/fill 50 50 50)
              (q/rect x hp-bar-y hp-bar-width hp-bar-height)

              (apply q/fill hp-color)
              (q/rect x hp-bar-y hp-width hp-bar-height)))

          ;; имя и счет
          (q/fill 255 255 255)
          (q/text-align :center :bottom)
          (q/text (cond
                    self? (str "YOU (" score ")")
                    boss? (str "BOSS (" score ")")
                    :else (str "Enemy (" score ")"))
                  center-x text-y)

          ;; пульсация босса при низком HP
          (when (and boss? (not dead?))
            (let [hp-percent (if (and (number? hp) (number? max-hp) (pos? max-hp))
                               (/ hp max-hp)
                               1)]
              (when (< hp-percent 0.3)
                (let [pulse (-> (System/currentTimeMillis)
                                (/ 200)
                                (mod 255)
                                int)]
                  (q/fill 255 255 255 pulse)
                  (q/rect (- x 2) (- y 2) (+ size 4) (+ size 4)))))))))))

(defn draw-bullet [bullet]
  (when (and bullet (map? bullet))
    (let [x (or (:x bullet) 0)
          y (or (:y bullet) 0)
          owner (:owner bullet)]
      (when (and (number? x) (number? y))
        (let [boss-bullet? (= owner "boss")]
          (if boss-bullet?
            (do
              (q/fill 255 0 0)
              (q/ellipse x y (+ shared/bullet-size 2) (+ shared/bullet-size 2))

              (q/fill 255 100 100 150)
              (q/ellipse x y (+ shared/bullet-size 6) (+ shared/bullet-size 6))

              (let [pulse (-> (System/currentTimeMillis)
                              (/ 100)
                              (mod 255)
                              int)]
                (q/fill 255 200 200 pulse)
                (q/ellipse x y (+ shared/bullet-size 10) (+ shared/bullet-size 10))))

            (do
              (q/fill 255 255 0)
              (q/ellipse x y shared/bullet-size shared/bullet-size)

              (q/fill 255 200 0 100)
              (q/ellipse x y
                         (+ shared/bullet-size 4)
                         (+ shared/bullet-size 4)))))))))

(defn draw-bonus [bonus]
  (when (and bonus (map? bonus))
    (let [x (or (:x bonus) 0)
          y (or (:y bonus) 0)]
      (when (and (number? x) (number? y))
        (let [bonus-type (or (keyword (:type bonus)) :health)
              center-x (+ x (/ shared/bonus-size 2))
              center-y (+ y (/ shared/bonus-size 2))
              pulse (-> (System/currentTimeMillis)
                        (/ 150)
                        (mod 255)
                        int)
              pulse-size (* (Math/sin (/ (System/currentTimeMillis) 500)) 3)]

          (case bonus-type
            :health
            (do
              (q/fill 0 255 0)
              (q/rect x y shared/bonus-size shared/bonus-size)
              (q/fill 0 200 0 100)
              (q/rect (- x pulse-size) (- y pulse-size)
                      (+ shared/bonus-size (* 2 pulse-size))
                      (+ shared/bonus-size (* 2 pulse-size)))
              (q/fill 255 255 255)
              (q/text-align :center :center)
              (q/text "H" center-x center-y))

            :speed
            (do
              (q/fill 0 0 255)
              (q/triangle x y
                          (+ x shared/bonus-size) y
                          center-x
                          (+ y shared/bonus-size))
              (q/fill 0 0 200 100)
              (q/triangle (- x pulse-size) (- y pulse-size)
                          (+ x shared/bonus-size pulse-size) (- y pulse-size)
                          center-x
                          (+ y shared/bonus-size pulse-size))
              (q/fill 255 255 255)
              (q/text-align :center :center)
              (q/text "S" center-x center-y))

            :damage
            (do
              (q/fill 255 0 0)
              (q/ellipse center-x center-y shared/bonus-size shared/bonus-size)
              (q/fill 200 0 0 100)
              (q/ellipse center-x center-y
                         (+ shared/bonus-size (* 2 pulse-size))
                         (+ shared/bonus-size (* 2 pulse-size)))
              (q/fill 255 255 255)
              (q/text-align :center :center)
              (q/text "D" center-x center-y))

            ;; default
            (do
              (q/fill 200 200 200)
              (q/rect x y shared/bonus-size shared/bonus-size)
              (q/fill 255 255 255)
              (q/text-align :center :center)
              (q/text "?" center-x center-y))))))))


(defn draw-background-effects []
  (q/stroke 100 100 100 50)
  (q/stroke-weight 0.5)

  (let [grid-size 50]
    (doseq [x (range 0 (inc shared/arena-width) grid-size)]
      (q/line x 0 x shared/arena-height))
    (doseq [y (range 0 (inc shared/arena-height) grid-size)]
      (q/line 0 y shared/arena-width y)))

  (q/no-stroke))

(defn draw-game-objects []
  (draw-background-effects)

  (doseq [bonus (remove nil? (state/get-bonuses))]
    (draw-bonus bonus))

  (doseq [bullet (remove nil? (state/get-bullets))]
    (draw-bullet bullet))

  (let [players (or (state/get-players) {})
        self-id (state/get-self-id)]
    (if (empty? players)
      (do
        (q/fill 255 255 255)
        (q/text-align :center :center)
        (q/text-size 24)
        (q/text "Waiting for players..."
                (/ shared/arena-width 2)
                (/ shared/arena-height 2))
        (q/text-size 14))

      (do
        (doseq [[pid player] players
                :when (and player (not= pid "boss"))]
          (draw-player player (= pid self-id) false))

        (when-let [boss (get players "boss")]
          (draw-player boss (= "boss" self-id) true))))))

(defn draw-debug-info []
  (when-let [boss (get (state/get-players) "boss")]
    (when-let [boss-pos (:position boss)]
      (let [x (:x boss-pos)
            y (:y boss-pos)]
        (when (and x y)
          (q/fill 255 255 255)
          (q/text-align :left :top)
          (q/text
           (str "BOSS AI: "
                (if (:dead boss) "DEAD" "ALIVE")
                " | HP: " (or (:hp boss) 0)
                " | Pos: [" (int x) "," (int y) "]")
           10 140))))))

(defn draw-game-over []
  (when-let [boss (get (state/get-players) "boss")]
    (when (:dead boss)
      (q/fill 0 0 0 200)
      (q/rect 0 0 shared/arena-width shared/arena-height)

      (q/fill 255 255 0)
      (q/text-align :center :center)
      (q/text-size 36)
      (q/text "BOSS DEFEATED!"
              (/ shared/arena-width 2)
              (- (/ shared/arena-height 2) 50))

      (q/text-size 24)
      (q/fill 255 255 255)
      (q/text "Victory!"
              (/ shared/arena-width 2)
              (+ (/ shared/arena-height 2) 20))

      (q/text-size 14))))

(defn draw-game-objects-with-effects []
  (draw-game-objects)
  (draw-debug-info)
  (draw-game-over))