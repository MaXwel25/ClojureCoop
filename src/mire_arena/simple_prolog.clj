(ns mire-arena.simple-prolog)

;; простой интерпретатор Prolog для базовой логики босса

(defn calculate-distance [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) 
                (Math/pow (- y2 y1) 2))))

(defn normalize-vector [dx dy]
  (let [magnitude (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? magnitude)
      [0 0]
      [(/ dx magnitude) (/ dy magnitude)])))

(defn prolog-boss-logic [boss-x boss-y players bullets bonuses]
  (let [;; исключаем босса из игроков
        other-players (filter (fn [[id _]] (not= id "boss")) players)
        
        ;; находим ближайшего живого игрока
        closest-player (->> other-players
                         (filter (fn [[_ player]] (not (:dead player))))
                         (map (fn [[id player]]
                                [id player (calculate-distance boss-x boss-y (:x player) (:y player))]))
                         (sort-by last)
                         first)]
    
    (if closest-player
      (let [[_ player distance] closest-player
            target-x (:x player)
            target-y (:y player)]
        
        (cond
          (< distance 100)
          ;; стреляем
          (let [dx (- target-x boss-x)
                dy (- target-y boss-y)
                [norm-dx norm-dy] (normalize-vector dx dy)]
            {:type :shoot :tx norm-dx :ty norm-dy})
          
          (< distance 300)
          ;; двигаемся к игроку
          (let [dx (- target-x boss-x)
                dy (- target-y boss-y)
                [norm-dx norm-dy] (normalize-vector dx dy)]
            {:type :move :dx norm-dx :dy norm-dy})
          
          :else
          ;; ждем
          {:type :wait}))
      {:type :wait})))