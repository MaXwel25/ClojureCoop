(ns mire-arena.client.state
  (:require [mire-arena.shared :as shared]))

(defonce game-state (atom {:players {}
                           :bullets []
                           :bonuses []
                           :self-id nil
                           :game-mode :multiplayer
                           :last-update (System/currentTimeMillis)}))

(defonce connection-status (atom :disconnected))
(defonce keys-pressed (atom #{}))
(defonce window-focused (atom true))
(defonce game-stats (atom {:fps 0
                           :last-frame-time 0
                           :packets-received 0
                           :boss-alive? false
                           :boss-hp 0
                           :boss-max-hp shared/boss-max-hp
                           :game-time 0
                           :players-alive 0
                           :players-total 0}))
(defonce debug-info (atom {:show-debug? false
                           :performance-stats {}
                           :network-latency 0
                           :last-ping-time 0}))

(def max-hp shared/max-hp)
(def arena-width shared/arena-width)
(def arena-height shared/arena-height)

(defn keywordize-keys [m]
  (when m
    (cond
      (map? m)
      (into {}
            (map (fn [[k v]]
                   [(cond
                      (string? k) (keyword k)
                      (keyword? k) k
                      :else (keyword (str k)))
                    (keywordize-keys v)])
                 m))
      (coll? m)
      (mapv keywordize-keys m)
      :else m)))

(defn- safe-parse-int [s default]
  (try
    (cond
      (number? s) (int s)
      (string? s) (Integer/parseInt s)
      (nil? s) default
      :else default)
    (catch Exception _
      default)))

(defn- safe-parse-double [s default]
  (try
    (cond
      (number? s) (double s)
      (string? s) (Double/parseDouble s)
      (nil? s) default
      :else default)
    (catch Exception _
      default)))

(defn fix-numeric-values [data]
  (when (and data (map? data))
    (let [is-boss? (= (:id data) "boss")
          pos (:position data)
          base-data (if (and pos (map? pos))
                      (-> data
                          (assoc :x (get pos :x))
                          (assoc :y (get pos :y))
                          (dissoc :position))
                      data)]
      (-> base-data
          (update :x #(safe-parse-int % 0))
          (update :y #(safe-parse-int % 0))
          (update :hp #(safe-parse-int % (if is-boss?
                                           shared/boss-max-hp
                                           shared/max-hp)))
          (update :score #(safe-parse-int % 0))
          (update :vx #(safe-parse-double % 0.0))
          (update :vy #(safe-parse-double % 0.0))
          (update :damage #(safe-parse-int % 0))
          (update :cooldown #(safe-parse-int % 0))
          (update :speed-buff #(safe-parse-int % 0))
          (update :damage-buff #(safe-parse-int % 0))
          (update :max-hp #(safe-parse-int % (if is-boss?
                                               shared/boss-max-hp
                                               shared/max-hp)))
          (update :dead #(if (nil? %) false (boolean %)))))))

(defn keywordize-players [players]
  (when players
    (into {}
          (map (fn [[id player]]
                 [id (-> player
                         keywordize-keys
                         (assoc :id id)
                         fix-numeric-values)])
               players))))

(defn keywordize-game-data [data]
  (when data
    (-> data
        (update :players keywordize-players)
        (update :bullets (fn [bullets]
                           (when bullets
                             (mapv (comp keywordize-keys fix-numeric-values) bullets))))
        (update :bonuses (fn [bonuses]
                           (when bonuses
                             (mapv (comp keywordize-keys fix-numeric-values) bonuses)))))))

(defn get-game-state [] @game-state)
(defn get-players [] (:players @game-state))
(defn get-bullets [] (:bullets @game-state))
(defn get-bonuses [] (:bonuses @game-state))
(defn get-self-id [] (:self-id @game-state))
(defn get-game-mode [] (:game-mode @game-state))
(defn get-connection-status [] @connection-status)
(defn get-keys-pressed [] @keys-pressed)
(defn get-window-focused [] @window-focused)
(defn get-game-stats [] @game-stats)
(defn get-debug-info [] @debug-info)

(defn get-self-player []
  (let [self-id (get-self-id)]
    (when self-id
      (get (:players @game-state) self-id))))

(defn get-player-by-id [player-id]
  (get (:players @game-state) player-id))

(defn get-boss []
  (get (:players @game-state) "boss"))

(defn is-boss-alive? []
  (let [boss (get-boss)]
    (and boss (not (:dead boss)) (> (or (:hp boss) 0) 0))))

(defn get-boss-hp []
  (let [boss (get-boss)]
    (if boss (or (:hp boss) 0) 0)))

(defn get-boss-max-hp []
  (or shared/boss-max-hp 1))

(defn get-boss-position []
  (let [boss (get-boss)]
    (if boss
      {:x (or (:x boss) 400) :y (or (:y boss) 300)}
      {:x 400 :y 300})))

(defn get-boss-status []
  (let [boss (get-boss)]
    (if boss
      {:alive? (not (:dead boss))
       :hp (or (:hp boss) 0)
       :max-hp (get-boss-max-hp)
       :position {:x (or (:x boss) 400) :y (or (:y boss) 300)}
       :score (or (:score boss) 0)
       :has-speed-buff? (boolean (:speed-buff boss))
       :has-damage-buff? (boolean (:damage-buff boss))}
      {:alive? false
       :hp 0
       :max-hp (get-boss-max-hp)
       :position {:x 400 :y 300}
       :score 0
       :has-speed-buff? false
       :has-damage-buff? false})))

(defn get-players-count []
  (count (get-players)))

(defn get-alive-players-count []
  (->> (get-players)
       (filter (fn [[_ player]] (not (:dead player))))
       count))

(defn get-player-rankings []
  (->> (get-players)
       (map (fn [[id player]]
              {:id id
               :score (or (:score player) 0)
               :hp (or (:hp player) 0)
               :dead? (:dead player false)
               :is-boss? (= id "boss")}))
       (sort-by :score >)))

(defn get-self-ranking []
  (let [self-id (get-self-id)
        rankings (get-player-rankings)
        ids (vec (map :id rankings))
        self-index (.indexOf ids self-id)]
    (if (>= self-index 0)
      {:rank (+ self-index 1)
       :total (count rankings)
       :player (nth rankings self-index)}
      nil)))

(defn get-bullets-by-owner [owner-id]
  (filter #(= (:owner %) owner-id) (get-bullets)))

(defn get-nearby-bonuses [x y radius]
  (filter (fn [bonus]
            (let [dx (- (or (:x bonus) 0) x)
                  dy (- (or (:y bonus) 0) y)
                  distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
              (< distance radius)))
          (get-bonuses)))

(defn update-derived-stats []
  (let [players (get-players)
        alive-count (get-alive-players-count)
        total-count (get-players-count)
        boss-status (get-boss-status)]
    (swap! game-stats assoc
           :boss-alive? (:alive? boss-status)
           :boss-hp (:hp boss-status)
           :boss-max-hp (:max-hp boss-status)
           :players-alive alive-count
           :players-total total-count)))

(defn update-fps [current-time]
  (let [last-time (or (:last-frame-time @game-stats) 0)
        fps (if (zero? last-time)
              0
              (int (/ 1000 (- current-time last-time))))]
    (swap! game-stats assoc
           :fps fps
           :last-frame-time current-time
           :game-time (+ (or (:game-time @game-stats) 0) (- current-time last-time)))))

(defn increment-packets-received []
  (swap! game-stats update :packets-received (fn [v] (inc (or v 0)))))

(defn update-network-latency [latency]
  (swap! debug-info assoc :network-latency (or latency 0)))

(defn update-performance-stats [stats]
  (swap! debug-info assoc :performance-stats (or stats {})))

(defn update-game-state [updates]
  (let [converted-updates (keywordize-game-data updates)
        old-self-id (:self-id @game-state)
        final-updates (if (contains? converted-updates :self-id)
                        converted-updates
                        (assoc converted-updates :self-id old-self-id))]
    (swap! game-state merge final-updates)
    (update-derived-stats)))

(defn set-players [players]
  (let [converted-players (keywordize-players players)]
    (swap! game-state assoc :players converted-players)
    (update-derived-stats)))

(defn set-bullets [bullets]
  (let [converted-bullets (when bullets
                            (mapv (comp keywordize-keys fix-numeric-values) bullets))]
    (swap! game-state assoc :bullets (or converted-bullets []))))

(defn set-bonuses [bonuses]
  (let [converted-bonuses (when bonuses
                            (mapv (comp keywordize-keys fix-numeric-values) bonuses))]
    (swap! game-state assoc :bonuses (or converted-bonuses []))))

(defn set-self-id [self-id]
  (swap! game-state assoc :self-id self-id))

(defn set-game-mode [mode]
  (swap! game-state assoc :game-mode mode))

(defn set-connection-status [status]
  (reset! connection-status status))

(defn add-key-pressed [key]
  (swap! keys-pressed conj key))

(defn remove-key-pressed [key]
  (swap! keys-pressed disj key))

(defn clear-keys-pressed []
  (reset! keys-pressed #{}))

(defn set-window-focused [focused]
  (reset! window-focused focused))

(defn toggle-debug-info []
  (swap! debug-info update :show-debug? not))

(defn set-debug-info [enabled?]
  (swap! debug-info assoc :show-debug? enabled?))

(defn add-debug-message [message]
  (when (:show-debug? @debug-info)
    (println "[DEBUG]" message)))

(defn get-game-time []
  (or (:game-time @game-stats) 0))

(defn get-time-since-last-update []
  (- (System/currentTimeMillis) (or (:last-update @game-state) (System/currentTimeMillis))))

(defn update-last-update-time []
  (swap! game-state assoc :last-update (System/currentTimeMillis)))

(defn get-boss-bullets []
  (get-bullets-by-owner "boss"))

(defn get-player-bullets []
  (let [self-id (get-self-id)]
    (get-bullets-by-owner self-id)))

(defn is-game-active? []
  (and (= (get-connection-status) :connected)
       (get-self-id)
       (get-self-player)))

(defn can-player-move? []
  (let [self (get-self-player)]
    (and self (not (:dead self)))))

(defn can-player-shoot? []
  (let [self (get-self-player)]
    (and self (not (:dead self)))))

(defn is-boss-low-hp? []
  (let [boss (get-boss)]
    (and boss (< (or (:hp boss) 0) 300))))

(defn is-boss-very-low-hp? []
  (let [boss (get-boss)]
    (and boss (< (or (:hp boss) 0) 100))))

(defn reset-game-state []
  (reset! game-state {:players {}
                      :bullets []
                      :bonuses []
                      :self-id nil
                      :game-mode :multiplayer
                      :last-update (System/currentTimeMillis)})
  (reset! keys-pressed #{})
  (reset! game-stats {:fps 0
                      :last-frame-time 0
                      :packets-received 0
                      :boss-alive? false
                      :boss-hp 0
                      :boss-max-hp (or shared/boss-max-hp 1)
                      :game-time 0
                      :players-alive 0
                      :players-total 0}))

(defn partial-reset []
  (swap! game-state assoc
         :bullets []
         :bonuses [])
  (swap! game-stats assoc
         :boss-hp (get-boss-hp)
         :boss-alive? (is-boss-alive?)))

(defn initialize-state []
  (reset-game-state)
  (println "Game state initialized"))

(defn init []
  (initialize-state))

(defn get-comprehensive-game-info []
  (let [self (get-self-player)
        boss (get-boss)
        stats (get-game-stats)]
    {:connection {:status (get-connection-status)
                  :self-id (get-self-id)
                  :window-focused (get-window-focused)}
     :player {:alive? (and self (not (:dead self)))
              :hp (if self (or (:hp self) 0) 0)
              :max-hp (or max-hp 1)
              :score (if self (or (:score self) 0) 0)
              :position (if self {:x (or (:x self) 0) :y (or (:y self) 0)} {:x 0 :y 0})
              :has-speed-buff? (boolean (:speed-buff self))
              :has-damage-buff? (boolean (:damage-buff self))}
     :boss {:alive? (is-boss-alive?)
            :hp (get-boss-hp)
            :max-hp (get-boss-max-hp)
            :low-hp? (is-boss-low-hp?)
            :very-low-hp? (is-boss-very-low-hp?)
            :position (get-boss-position)}
     :game {:fps (or (:fps stats) 0)
            :players-alive (or (:players-alive stats) 0)
            :players-total (or (:players-total stats) 0)
            :bullets-count (count (get-bullets))
            :bonuses-count (count (get-bonuses))
            :game-time (or (:game-time stats) 0)}
     :ranking (get-self-ranking)}))