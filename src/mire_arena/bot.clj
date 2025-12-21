(ns mire-arena.bot
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; путь к Prolog-файлу
(def prolog-file "resources/arena_bot.pl")

;; кэш для результатов AI
(defonce last-ai-call (atom 0))
(defonce ai-cooldown-ms 500) ;; ТО ЖЕ САМОЕ ЧТО boss-ai-tick-ms
(defonce last-ai-result (atom nil))

(defn- run-prolog
  "Выполняет Prolog-запрос и возвращает результат как строку"
  [query]
  (let [{:keys [out err exit]} (shell/sh
                                "swipl"
                                "-q"
                                "-s" prolog-file
                                "-g" query
                                "-t" "halt")]
    (when (not= exit 0)
      (println " Prolog error:" err))
    (str/trim out)))

(defn prolog-boss-ai
  "Вызывает Prolog и возвращает действие босса"
  [boss players bullets bonuses]
  (let [x (int (:x boss))
        y (int (:y boss))
        ;; формируем список игроков для Prolog
        players-list (for [[id player] (dissoc players "boss")
                           :when (not (:dead player))]
                       (str "player('" 
                            id "'," 
                            (int (:x player)) "," 
                            (int (:y player)) "," 
                            (:hp player) "," 
                            (if (= (:id player) "boss") 1000 100) 
                            "," 
                            "false)"))
        ;; формируем запрос
        query (if (empty? players-list)
                (str "bot_action(" x "," y ",[],[],[],Action), writeln(Action).")
                (str "bot_action(" x "," y ",[" (str/join "," players-list) "],[],[],Action), writeln(Action)."))]
    
    ;; выводим только раз в 10 вызовов
    (when (zero? (mod (rand-int 100) 10))
      (println "-+-  Prolog query (sample):" (subs query 0 (min 100 (count query)))))
    
    (let [result (run-prolog query)]
      (cond
        (str/starts-with? result "move")
        (let [nums (re-seq #"-?\d+\.?\d*" result)
              [dx dy] (when (>= (count nums) 2)
                        [(Double/parseDouble (nth nums 0)) 
                         (Double/parseDouble (nth nums 1))])]
          (if dx
            {:type :move :dx dx :dy dy}
            {:type :wait}))
        
        (str/starts-with? result "shoot")
        (let [nums (re-seq #"-?\d+\.?\d*" result)
              [tx ty] (when (>= (count nums) 2)
                        [(Double/parseDouble (nth nums 0)) 
                         (Double/parseDouble (nth nums 1))])]
          (if tx
            {:type :shoot :tx tx :ty ty}
            {:type :wait}))
        
        :else
        {:type :wait}))))

(defn create-boss []
  {:id "boss"
   :x 400 :y 300
   :hp 1000
   :max-hp 1000
   :last-shot 0
   :dead false
   :type :boss})

(defn simple-boss-ai [boss players bullets bonuses]
  (let [now (System/currentTimeMillis)
        last-call @last-ai-call]
    
    ;; проверяем кэш и таймер
    (if (and @last-ai-result (< (- now last-call) ai-cooldown-ms))
      (do
        ;; используем кэшированный результат
        (when (zero? (mod (rand-int 100) 20))
          (println "-+- Boss AI (cached)"))
        @last-ai-result)
      (try
        ;; вызываем Prolog
        (reset! last-ai-call now)
        (let [result (prolog-boss-ai boss players bullets bonuses)]
          (reset! last-ai-result result)
          ;; выводим только иногда
          (when (zero? (mod (rand-int 100) 5))
            (println "-+- Boss action:" (if (= (:type result) :move) 
                                         "move" 
                                         (if (= (:type result) :shoot) 
                                           "shoot" 
                                           "wait"))))
          result)
        (catch Exception e
          (println "  Prolog AI failed:" (.getMessage e))
          {:type :wait})))))

(defn update-boss [game-state]
  (let [boss (get-in game-state [:players "boss"])]
    (if (and boss (not (:dead boss)))
      (let [action (simple-boss-ai boss
                                   (:players game-state)
                                   (:bullets game-state)
                                   (:bonuses game-state))]
        (case (:type action)
          :move
          (let [dx (:dx action)
                dy (:dy action)
                len (Math/sqrt (+ (* dx dx) (* dy dy)))
                [nx ny] (if (> len 0) [(/ dx len) (/ dy len)] [0 0])]
            (-> game-state
                (assoc-in [:players "boss" :x]
                          (-> (:x boss) (+ (* nx 3)) (max 0) (min 765)))
                (assoc-in [:players "boss" :y]
                          (-> (:y boss) (+ (* ny 3)) (max 0) (min 565)))))
          
          :shoot
          (let [now (System/currentTimeMillis)
                last-shot (:last-shot boss 0)]
            (if (>= (- now last-shot) 800) ; boss-bullet-cooldown
              (let [tx (:tx action)
                    ty (:ty action)
                    len (Math/sqrt (+ (* tx tx) (* ty ty)))
                    [dx dy] (if (> len 0) [(/ tx len) (/ ty len)] [0 0])
                    bullet-id (str (:next-bullet-id game-state))
                    bullet-x (+ (:x boss) (/ 35 2)) ; boss-size
                    bullet-y (+ (:y boss) (/ 35 2))]
                (-> game-state
                    (assoc-in [:bullets bullet-id]
                              {:id bullet-id
                               :x bullet-x :y bullet-y
                               :dx dx :dy dy
                               :owner "boss"
                               :created-at now})
                    (update :next-bullet-id inc)
                    (assoc-in [:players "boss" :last-shot] now)))
              game-state))
          
          :wait
          game-state))
      game-state)))