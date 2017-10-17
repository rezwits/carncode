(ns meccg.deckbuilder
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put! <! timeout] :as async]
            [clojure.string :refer [split split-lines join escape]]
            [meccg.appstate :refer [app-state]]
            [meccg.auth :refer [authenticated] :as auth]
            [meccg.cardbrowser :refer [cards-channel image-url card-view] :as cb]
            [meccg.account :refer [load-alt-arts]]
            [meccg.ajax :refer [POST GET]]
            [goog.string :as gstring]
            [goog.string.format]
            [superstring.core :as str]))

(def select-channel (chan))
(def zoom-channel (chan))
(def INFINITY 2147483647)

(defn num->percent
  "Converts an input number to a percent of the second input number for display"
  [num1 num2]
  (gstring/format "%.0f" (* 100 (float (/ num1 num2)))))

(defn identical-cards? [cards]
  (let [name (:NameEN (first cards))]
    (every? #(= (:NameEN %) name) cards)))

(defn found? [query cards]
  (some #(if (= (str/strip-accents (.toLowerCase (:NameEN %))) query) %) cards))

(defn is-draft-id?
  "Check if the specified id is a draft identity"
  [identity]
  (= "Draft" (:Set identity)))

(defn is-prof-prog?
  "Check if ID is The Professor and card is a Program"
  [deck card]
  (and (= "03029" (get-in deck [:identity :code]))
       (= "Program" (:type card))))

(defn id-inf-limit
  "Returns influence limit of an identity or INFINITY in case of draft IDs."
  [identity]
  (if (is-draft-id? identity) INFINITY (:influencelimit identity)))

(defn not-alternate [card]
  (if (= (:Set card) "Alternates")
    (some #(and (not= (:Set %) "Alternates")
                (= (:NameEN %) (:NameEN card))
                %)
          (:cards @app-state))
    card))

(defn- check-mwl-map
  "Check if card is in specified mwl map"
  [mwl-map card]
  (->> card
       not-alternate
       :code
       keyword
       (contains? (:cards mwl-map))))

(defn- get-mwl-value
  "Get universal influence for card"
  ([card] (get-mwl-value (first (filter :active (:mwl @app-state))) card))
  ([mwl-map card]
   (->> card
        not-alternate
        :code
        keyword
        (get (:cards mwl-map)))))

(defn mostwanted?
  "Returns true if card is on Most Wanted NAPD list."
  ([card]
   (let [mwl-list (:mwl @app-state)
         active-mwl (first (filter :active mwl-list))]
     (check-mwl-map active-mwl card)))
  ([mwl-code card]
   (let [mwl-list (:mwl @app-state)
         mwl-map (first (filter #(= mwl-code (:code %)) mwl-list))]
     (check-mwl-map mwl-map card))))

(defn card-count [cards]
  (reduce #(+ %1 (:qty %2)) 0 cards))

(defn noinfcost? [identity card]
  (or (= (:faction card) (:faction identity))
      (= 0 (:factioncost card)) (= INFINITY (id-inf-limit identity))))

(defn search [query cards]
  (filter #(if (= (.indexOf (str/strip-accents (.toLowerCase (:NameEN %))) query) -1) false true) cards))

(defn alt-art?
  "Removes alt-art cards from the search if user is not :special"
  [card]
  (or (get-in @app-state [:user :special])
      (not= "Alternates" (:Set card))))

(defn take-best-card
  "Returns a non-rotated card from the list of cards or a random rotated card from the list"
  [cards]
  (let [non-rotated (filter #(not (:rotated %)) cards)]
    (if (not-empty non-rotated)
      (first non-rotated)
      (first cards))))

(defn lookup
  "Lookup the card title (query) looking at all cards on specified side"
  [side query]
  (let [q (str/strip-accents (.toLowerCase query))
        cards (filter #(and (= (:Alignment %) side) (alt-art? %))
                      (:cards @app-state))]
    (if-let [all-matches (filter #(= (-> % :NameEN .toLowerCase str/strip-accents) q) cards)]
      (take-best-card all-matches)
      (loop [i 2 matches cards]
        (let [subquery (subs q 0 i)]
          (cond (zero? (count matches)) query
                (or (= (count matches) 1) (identical-cards? matches)) (first matches)
                (found? subquery matches) (found? subquery matches)
                (<= i (count query)) (recur (inc i) (search subquery matches))
                :else query))))))

(defn parse-identity
  "Parse an id to the corresponding card map - only care about side and name for now"
  [{:keys [side title]}]
  (lookup side title))

(defn parse-line [side line]
  (let [tokens (split line " ")
        qty (js/parseInt (first tokens))
        cardname (join " " (rest tokens))]
    (when-not (js/isNaN qty)
      {:qty (min qty 6) :card (lookup side cardname)})))

(defn deck-string->list
  "Turn a raw deck string into a list of {:qty :NameEN}"
  [deck-string]
  (letfn [(line-reducer [coll line]
            (let [[qty & cardname] (split line " ")
                  qty (js/parseInt qty)
                  title (join " " cardname)]
              (if (js/isNaN qty)
                coll
                (conj coll {:qty qty :card title}))))]
    (reduce line-reducer [] (split-lines deck-string))))

(defn collate-deck
  "Takes a list of {:qty n :card title} and returns list of unique titles and summed n for same title"
  [card-list]
  ;; create a backing map of title to {:qty n :card title} and update the
  (letfn [(duphelper [currmap line]
            (let [title (:card line)
                  curr-qty (get-in currmap [title :qty] 0)
                  line (update line :qty #(+ % curr-qty))]
              (assoc currmap title line)))]
    (vals (reduce duphelper {} card-list))))

(defn lookup-deck
  "Takes a list of {:qty n :card title} and looks up each title and replaces it with the corresponding cardmap"
  [side card-list]
  (let [card-list (collate-deck card-list)
        card-lookup (partial lookup side)]
    ;; lookup each card and replace title with cardmap
    (map #(update % :card card-lookup) card-list)))

(defn parse-deck-string
  "Parses a string containing the decklist and returns a list of lines {:qty :card}"
  [side deck-string]
  (let [raw-deck-list (deck-string->list deck-string)]
    (lookup-deck side raw-deck-list)))

(defn faction-label
  "Returns faction of a card as a lowercase label"
  [card]
  (if (nil? (:faction card))
    "neutral"
    (-> card :faction str/strip-accents .toLowerCase (.replace " " "-"))))

(defn allowed?
  "Checks if a card is allowed in deck of a given identity - not accounting for influence"
  [card {:keys [side faction code] :as identity}]
  (and (not= (:Secondary card) "Avatar")
       (= (:side card) side)
       (or (not= (:type card) "Agenda")
           (= (:faction card) "Neutral")
           (= (:faction card) faction)
           (is-draft-id? identity))
       (or (not= code "03002") ; Custom Biotics: Engineered for Success
           (not= (:faction card) "Jinteki"))))

(defn load-decks [decks]
  (swap! app-state assoc :decks decks)
  (put! select-channel (first (sort-by :date > decks)))
  (swap! app-state assoc :decks-loaded true))

(defn process-decks
  "Process the raw deck from the database into a more useful format"
  [decks]
  (for [deck decks]
    (let [identity (parse-identity (:identity deck))
          cards (lookup-deck (:identity identity) (:cards deck))]
      (assoc deck :identity identity :cards cards))))



(defn distinct-by [f coll]
  (letfn [(step [xs seen]
            (lazy-seq (when-let [[x & more] (seq xs)]
                        (let [k (f x)]
                          (if (seen k)
                            (step more seen)
                            (cons x (step more (conj seen k))))))))]
    (step coll #{})))

(defn side-identities [side]
  (->> (:cards @app-state)
       (filter #(and (= (:Alignment %) side)
                     (= (:Secondary %) "Avatar")
                     (alt-art? %)))
       (distinct-by :NameEN)))

(defn deck->str [owner]
  (let [cards (om/get-state owner [:deck :cards])
        str (reduce #(str %1 (:qty %2) " " (get-in %2 [:card :NameEN]) "\n") "" cards)]
    (om/set-state! owner :deck-edit str)))

(defn mostwantedval
  "Returns a map of faction keywords to number of MWL universal influence spent from the faction's cards."
  [deck]
  (let [cards (:cards deck)
        mwlhelper (fn [currmap line]
                    (let [card (:card line)
                          qty (if (is-prof-prog? deck card)
                                (- (:qty line) 1)
                                (:qty line))]
                      (if (mostwanted? card)
                        (update-in currmap [(keyword (faction-label card))]
                                   (fnil (fn [curmwl] (+ curmwl (* (get-mwl-value card) qty))) 0))
                        currmap)))]
    (reduce mwlhelper {} cards)))

;;; Helpers for Alliance cards
(defn is-alliance?
  "Checks if the card is an alliance card"
  [card]
  ;; All alliance cards
  (let [ally-cards #{"10013" "10018" "10019" "10029" "10038" "10067" "10068" "10071" "10072" "10076" "10094" "10109"}
        card-code (:code (:card card))]
    (ally-cards card-code)))

(defn default-alliance-is-free?
  "Default check if an alliance card is free - 6 non-alliance cards of same faction."
  [cards line]
  (<= 6 (card-count (filter #(and (= (get-in line [:card :faction])
                                     (get-in % [:card :faction]))
                                  (not (is-alliance? %)))
                            cards))))

(defn alliance-is-free?
  "Checks if an alliance card is free"
  [cards {:keys [card] :as line}]
  (case (:code card)
    (list
      "10013"                                               ; Heritage Committee
      "10029"                                               ; Product Recall
      "10067"                                               ; Jeeves Model Bioroids
      "10068"                                               ; Raman Rai
      "10071"                                               ; Salem's Hospitality
      "10072"                                               ; Executive Search Firm
      "10094"                                               ; Consulting Visit
      "10109")                                              ; Ibrahim Salem
    (default-alliance-is-free? cards line)
    "10018"                                                 ; Mumba Temple
    (>= 15 (card-count (filter #(= "ICE" (:type (:card %))) cards)))
    "10019"                                                 ; Museum of History
    (<= 50 (card-count cards))
    "10038"                                                 ; PAD Factory
    (= 3 (card-count (filter #(= "01109" (:code (:card %))) cards)))
    "10076"                                                 ; Mumbad Virtual Tour
    (<= 7 (card-count (filter #(= "Asset" (:type (:card %))) cards)))
    ;; Not an alliance card
    false))

;;; Influence map helpers
;; Note: line is a map with a :card and a :qty
(defn line-base-cost
  "Returns the basic influence cost of a deck-line"
  [identity-faction {:keys [card qty]}]
  (let [card-faction (:faction card)]
    (if (= identity-faction card-faction)
      0
      (* qty (:factioncost card)))))

(defn line-influence-cost
  "Returns the influence cost of the specified card"
  [deck line]
  (let [identity-faction (get-in deck [:identity :faction])
        base-cost (line-base-cost identity-faction line)]
    ;; Do not care about discounts if the base cost is 0 (in faction or free neutral)
    (if (zero? base-cost)
      0
      (cond
        ;; The Professor: Keeper of Knowledge - discount influence cost of first copy of each program
        (is-prof-prog? deck (:card line))
        (- base-cost (get-in line [:card :factioncost]))
        ;; Check if the card is Alliance and fulfills its requirement
        (alliance-is-free? (:cards deck) line)
        0
        :else
        base-cost))))

(defn influence-map
  "Returns a map of faction keywords to influence values from the faction's cards."
  [deck]
  (letfn [(infhelper [infmap line]
            (let [inf-cost (line-influence-cost deck line)
                  faction (keyword (faction-label (:card line)))]
              (update infmap faction #(+ (or % 0) inf-cost))))]
    (reduce infhelper {} (:cards deck))))

(defn universalinf-count
  "Returns total number universal influence in a deck."
  [deck]
  (apply + (vals (mostwantedval deck))))

(defn influence-count
  "Returns sum of influence count used by a deck."
  [deck]
  (apply + (vals (influence-map deck))))

(defn min-deck-size
  "Contains implementation-specific decksize adjustments, if they need to be different from printed ones."
  [identity]
  (:minimumdecksize identity))

(defn min-agenda-points [deck]
  (let [size (max (card-count (:cards deck)) (min-deck-size (:identity deck)))]
    (+ 2 (* 2 (quot size 5)))))

(defn agenda-points [{:keys [cards]}]
  (reduce #(if-let [point (get-in %2 [:card :agendapoints])]
             (+ (* point (:qty %2)) %1) %1) 0 cards))

(defn legal-num-copies?
  "Returns true if there is a legal number of copies of a particular card."
  [identity {:keys [qty card]}]
  (or (is-draft-id? identity)
      (<= qty (or (:limited card) 3))))

(defn valid? [{:keys [identity cards] :as deck}]
  (and (>= (card-count cards) (min-deck-size identity))
       (<= (influence-count deck) (id-inf-limit identity))
       (every? #(and (allowed? (:card %) identity)
                     (legal-num-copies? identity %)) cards)
       (or (= (:Alignment identity) "Minion")
           (let [min (min-agenda-points deck)]
             (<= min (agenda-points deck) (inc min))))))

(defn released?
  "Returns false if the card comes from a spoiled set or is out of competitive rotation."
  [sets card]
  (let [card-set (:Set card)
        rotated (:rotated card)
        date (some #(when (= (:name %) card-set) (:available %)) sets)]
    (and (not rotated)
         (not= date "")
         (< date (.toJSON (js/Date.))))))

;; 1.1.1.1 and Cache Refresh validation
(defn group-cards-from-restricted-sets
  "Return map (big boxes and datapacks) of used sets that are restricted by given format"
  [sets allowed-sets deck]
  (let [restricted-cards (remove (fn [card] (some #(= (:Set (:card card)) %) allowed-sets)) (:cards deck))
        restricted-sets (group-by (fn [card] (:Set (:card card))) restricted-cards)
        sorted-restricted-sets (reverse (sort-by #(count (second %)) restricted-sets))
        [restricted-bigboxes restricted-datapacks] (split-with (fn [[setname cards]] (some #(when (= (:name %) setname) (:bigbox %)) sets)) sorted-restricted-sets)]
    { :bigboxes restricted-bigboxes :datapacks restricted-datapacks }))

(defn cards-over-one-core
  "Returns cards in deck that require more than single box."
  [deck]
  (let [one-box-num-copies? (fn [{:keys [qty card]}] (<= qty (or (:packquantity card) 3)))]
    (remove one-box-num-copies? (:cards deck))))

(defn sets-in-two-newest-cycles
  "Returns sets in two newest cycles of released datapacks - for Cache Refresh format"
  [sets]
  (let [cycles (group-by :cycle (remove :bigbox sets))
        cycle-release-date (reduce-kv (fn [result cycle sets-in-cycle] (assoc result cycle (apply min (map :available sets-in-cycle)))) {} cycles)
        valid-cycles (map first (take-last 2 (sort-by last (filter (fn [[cycle date]] (and (not= date "") (< date (.toJSON (js/Date.))))) cycle-release-date))))]
    (map :name (filter (fn [set] (some #(= (:cycle set) %) valid-cycles)) sets))))

(defn cache-refresh-legal
  "Returns true if deck is valid under Cache Refresh rules."
  [sets deck]
  (let [over-one-core (cards-over-one-core deck)
        valid-sets (concat ["Core Set" "Terminal Directive"] (sets-in-two-newest-cycles sets))
        deck-with-id (assoc deck :cards (cons {:card (:identity deck) } (:cards deck))) ;identity should also be from valid sets
        restricted-sets (group-cards-from-restricted-sets sets valid-sets deck-with-id)
        restricted-bigboxes (rest (:bigboxes restricted-sets)) ;one big box is fine
        restricted-datapacks (:datapacks restricted-sets)
        example-card (fn [cardlist] (get-in (first cardlist) [:card :NameEN]))
        reasons {
                 :onecore (when (not= (count over-one-core) 0) (str "Only one Core Set permitted - check: " (example-card over-one-core)))
                 :bigbox (when (not= (count restricted-bigboxes) 0) (str "Only one Deluxe Expansion permitted - check: " (example-card (second (first restricted-bigboxes)))))
                 :datapack (when (not= (count restricted-datapacks) 0) (str "Only two most recent cycles permitted - check: " (example-card (second (first restricted-datapacks)))))
                 }]
    { :legal (not-any? val reasons) :reason (join "\n" (filter identity (vals reasons))) }))

(defn onesies-legal
  "Returns true if deck is valid under 1.1.1.1 format rules."
  [sets deck]
  (let [over-one-core (cards-over-one-core deck)
        valid-sets ["Core Set"]
        restricted-sets (group-cards-from-restricted-sets sets valid-sets deck)
        restricted-bigboxes (rest (:bigboxes restricted-sets)) ;one big box is fine
        restricted-datapacks (rest (:datapacks restricted-sets)) ;one datapack is fine
        only-one-offence (>= 1 (apply + (map count [over-one-core restricted-bigboxes restricted-datapacks]))) ;one offence is fine
        example-card (fn [cardlist] (join ", " (map #(get-in % [:card :NameEN]) (take 2 cardlist))))
        reasons (if only-one-offence {} {
                                         :onecore (when (not= (count over-one-core) 0) (str "Only one Core Set permitted - check: " (example-card over-one-core)))
                                         :bigbox (when (not= (count restricted-bigboxes) 0) (str "Only one Deluxe Expansion permitted - check: " (example-card (second (first restricted-bigboxes)))))
                                         :datapack (when (not= (count restricted-datapacks) 0) (str "Only one Datapack permitted - check: " (example-card (second (first restricted-datapacks)))))
                                         })]
    { :legal (not-any? val reasons) :reason (join "\n" (filter identity (vals reasons))) }))

(defn mwl-legal?
  "Returns true if the deck's influence fits within NAPD MWL universal influence restrictions."
  [deck]
  (<= (+ (universalinf-count deck) (influence-count deck)) (id-inf-limit (:identity deck))))

(defn only-in-rotation?
  "Returns true if the deck doesn't contain any cards outside of current rotation."
  [sets deck]
  (and (every? #(released? sets (:card %)) (:cards deck))
       (released? sets (:identity deck))))

(defn edit-deck [owner]
  (let [deck (om/get-state owner :deck)]
    (om/set-state! owner :old-deck deck)
    (om/set-state! owner :edit true)
    (deck->str owner)
    (-> owner (om/get-node "viewport") js/$ (.addClass "edit"))
    (try (js/ga "send" "event" "deckbuilder" "edit") (catch js/Error e))
    (go (<! (timeout 500))
        (-> owner (om/get-node "deckname") js/$ .select))))

(defn end-edit [owner]
  (om/set-state! owner :edit false)
  (om/set-state! owner :query "")
  (-> owner (om/get-node "viewport") js/$ (.removeClass "edit")))

(defn handle-edit [owner]
  (let [text (.-value (om/get-node owner "deck-edit"))
        side (om/get-state owner [:deck :identity :Alignment])
        cards (parse-deck-string side text)]
    (om/set-state! owner :deck-edit text)
    (om/set-state! owner [:deck :cards] cards)))

(defn wizard-edit [owner]
  (if (om/get-state owner :vs-wizard)
    (om/set-state! owner :vs-wizard false)
    (om/set-state! owner :vs-wizard true))
  (if (and (om/get-state owner :vs-wizard) (om/get-state owner :vs-minion))
    (om/set-state! owner :vs-fallen true))
  )

(defn minion-edit [owner]
  (if (om/get-state owner :vs-minion)
    (om/set-state! owner :vs-minion false)
    (om/set-state! owner :vs-minion true))
  (if (and (om/get-state owner :vs-wizard) (om/get-state owner :vs-minion))
    (om/set-state! owner :vs-fallen true))
  )

(defn fallen-edit [owner]
  (if (and (om/get-state owner :vs-wizard) (om/get-state owner :vs-minion))
    (om/set-state! owner :vs-fallen true)
    (if (om/get-state owner :vs-fallen)
      (om/set-state! owner :vs-fallen false)
      (om/set-state! owner :vs-fallen true))
    )
  )

(defn cancel-edit [owner]
  (end-edit owner)
  (go (let [deck (om/get-state owner :old-deck)
            all-decks (process-decks (:json (<! (GET (str "/data/decks")))))]
        (load-decks all-decks)
        (put! select-channel deck))))

(defn delete-deck [owner]
  (om/set-state! owner :delete true)
  (deck->str owner)
  (-> owner (om/get-node "viewport") js/$ (.addClass "delete"))
  (try (js/ga "send" "event" "deckbuilder" "delete") (catch js/Error e)))

(defn end-delete [owner]
  (om/set-state! owner :delete false)
  (-> owner (om/get-node "viewport") js/$ (.removeClass "delete")))

(defn handle-delete [cursor owner]
  (authenticated
    (fn [user]
      (let [deck (om/get-state owner :deck)]
        (try (js/ga "send" "event" "deckbuilder" "delete") (catch js/Error e))
        (go (let [response (<! (POST "/data/decks/delete" deck :json))]))
        (do
          (om/transact! cursor :decks (fn [ds] (remove #(= deck %) ds)))
          (om/set-state! owner :deck (first (sort-by :date > (:decks @cursor))))
          (end-delete owner))))))

(defn new-deck [side owner]
  (om/set-state! owner :deck {:name "New deck" :cards [] :resources []
                              :identity (-> side side-identities first)})
  (try (js/ga "send" "event" "deckbuilder" "new" side) (catch js/Error e))
  (edit-deck owner))

(defn save-deck [cursor owner]
  (authenticated
    (fn [user]
      (end-edit owner)
      (let [deck (assoc (om/get-state owner :deck) :date (.toJSON (js/Date.)))
            decks (remove #(= (:_id deck) (:_id %)) (:decks @app-state))
            cards (for [card (:cards deck) :when (get-in card [:card :NameEN])]
                    {:qty (:qty card) :card (get-in card [:card :NameEN])})
            ;; only include keys that are relevant, currently title and side, includes code for future-proofing
            identity (select-keys (:identity deck) [:NameEN :Alignment :code])
            data (assoc deck :cards cards :identity identity)]
        (try (js/ga "send" "event" "deckbuilder" "save") (catch js/Error e))
        (go (let [new-id (get-in (<! (POST "/data/decks/" data :json)) [:json :_id])
                  new-deck (if (:_id deck) deck (assoc deck :_id new-id))
                  all-decks (process-decks (:json (<! (GET (str "/data/decks")))))]
              (om/update! cursor :decks (conj decks new-deck))
              (om/set-state! owner :deck new-deck)
              (load-decks all-decks)))))))

(defn html-escape [st]
  (escape st {\< "&lt;" \> "&gt;" \& "&amp;" \" "#034;"}))

;; Dot definitions
(def zws "\u200B")                                          ; zero-width space for wrapping dots
(def influence-dot (str "●" zws))                           ; normal influence dot
(def mwl-dot (str "★" zws))                                 ; influence penalty from MWL
(def alliance-dot (str "○" zws))                            ; alliance free-inf dot

(def banned-span
  [:span.invalid {:title "Removed"} " " banned-dot])

(def restricted-span
  [:span {:title "Restricted"} " " restricted-dot])

(def rotated-span
  [:span.casual {:title "Rotated"} " " rotated-dot])

(defn- make-dots
  "Returns string of specified dots and number. Uses number for n > 20"
  [dot n]
  (if (<= 20 n)
    (str n dot)
    (join (conj (repeat n dot) ""))))

(defn influence-dots
  "Returns a string with UTF-8 full circles representing influence."
  [num]
  (make-dots influence-dot num))

(defn restricted-dots
  "Returns a string with UTF-8 empty circles representing MWL restricted cards."
  [num]
  (make-dots mwl-dot num))

(defn alliance-dots
  [num]
  (make-dots alliance-dot num))

(defn- dots-html
  "Make a hiccup-ready vector for the specified dot and cost-map (influence or mwl)"
  [dot cost-map]
  (for [factionkey (sort (keys cost-map))]
    [:span.influence {:class (name factionkey)} (make-dots dot (factionkey cost-map))]))

(defn card-influence-html
  "Returns hiccup-ready vector with dots for influence as well as restricted / rotated / banned symbols"
  [card qty in-faction allied?]
  (let [influence (* (:factioncost card) qty)
        banned (banned? card)
        restricted (restricted? card)
        rotated (:rotated card)]
    (list " "
          (when (and (not banned) (not in-faction))
            [:span.influence {:class (faction-label card)}
             (if allied?
               (alliance-dots influence)
               (influence-dots influence))])
          (if banned
            banned-span
            [:span
             (when restricted restricted-span)
             (when rotated rotated-span)]))))

(defn influence-html
  "Returns hiccup-ready vector with dots colored appropriately to deck's influence."
  [deck]
  (dots-html influence-dot (influence-map deck)))

(defn restricted-html
  "Returns hiccup-ready vector with dots colored appropriately to deck's MWL restricted cards."
  [deck]
  (dots-html mwl-dot (mostwantedval deck)))

(defn deck-status-label
  [sets deck]
  (cond
    (and (mwl-legal? deck) (valid? deck) (only-in-rotation? sets deck)) "legal"
    (valid? deck) "casual"
    :else "invalid"))

(defn deck-status-span
  "Returns a [:span] with standardized message and colors depending on the deck validity."
  ([sets deck] (deck-status-span sets deck false))
  ([sets deck tooltip?] (deck-status-span sets deck tooltip? false))
  ([sets deck tooltip? onesies-details?]
   (let [status (deck-status-label sets deck)
         valid (valid? deck)
         mwl (mwl-legal? deck)
         rotation (only-in-rotation? sets deck)
         cache-refresh (cache-refresh-legal sets deck)
         onesies (onesies-legal sets deck)
         message (case status
                   "legal" "Tournament legal"
                   "casual" "Casual play only"
                   "invalid" "Invalid")]
     [:span.deck-status {:class status} message
      (when tooltip?
        [:div.status-tooltip.blue-shade
         [:div {:class (if valid "legal" "invalid")}
          [:span.tick (if valid "✔" "✘")] "Basic deckbuilding rules"]
         [:div {:class (if mwl "legal" "invalid")}
          [:span.tick (if mwl "✔" "✘")] "NAPD Most Wanted List"]
         [:div {:class (if rotation "legal" "invalid")}
          [:span.tick (if rotation "✔" "✘")] "Only released cards"]
         [:div {:class (if (:legal cache-refresh) "legal" "invalid") :NameEN (if onesies-details? (:reason cache-refresh)) }
          [:span.tick (if (:legal cache-refresh) "✔" "✘")] "Cache Refresh compliant"]
         [:div {:class (if (:legal onesies) "legal" "invalid") :NameEN (if onesies-details? (:reason onesies))}
          [:span.tick (if (:legal onesies) "✔" "✘") ] "1.1.1.1 format compliant"]])])))

(defn match [identity query]
  (if (empty? query)
    []
    (let [cards (->> (:cards @app-state)
                     (filter #(and (allowed? % identity)
                                   (not= "Special" (:Set %))
                                   (alt-art? %)))
                     (distinct-by :NameEN))]
      (take 10 (filter #(not= (.indexOf (str/strip-accents (.toLowerCase (:NameEN %))) (str/strip-accents(.toLowerCase query))) -1) cards)))))

(defn handle-keydown [owner event]
  (let [selected (om/get-state owner :selected)
        matches (om/get-state owner :matches)]
    (case (.-keyCode event)
      38 (when (pos? selected)
           (om/update-state! owner :selected dec))
      40 (when (< selected (dec (count matches)))
           (om/update-state! owner :selected inc))
      (9 13) (when-not (= (om/get-state owner :query) (:NameEN (first matches)))
               (.preventDefault event)
               (-> ".deckedit .qty" js/$ .select)
               (om/set-state! owner :query (:NameEN (nth matches selected))))
      (om/set-state! owner :selected 0))))

(defn handle-add [owner event]
  (.preventDefault event)
  (let [qty (js/parseInt (om/get-state owner :quantity))
        card (nth (om/get-state owner :matches) (om/get-state owner :selected))
        best-card (lookup (:Alignment card) (:NameEN card))]
    (if (js/isNaN qty)
      (om/set-state! owner :quantity 3)
      (do (put! (om/get-state owner :edit-channel)
                {:qty qty
                 :card best-card})
          (om/set-state! owner :quantity 3)
          (om/set-state! owner :query "")
          (-> ".deckedit .lookup" js/$ .select)))))

(defn card-lookup [{:keys [cards]} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:query ""
       :matches []
       :quantity 3
       :selected 0})

    om/IRenderState
    (render-state [this state]
      (sab/html
        [:p
         [:h3 "Add cards"]
         [:form.card-search {:on-submit #(handle-add owner %)}
          [:input.lookup {:type "text" :placeholder "Card name" :value (:query state)
                          :on-change #(om/set-state! owner :query (.. % -target -value))
                          :on-key-down #(handle-keydown owner %)}]
          " x "
          [:input.qty {:type "text" :value (:quantity state)
                       :on-change #(om/set-state! owner :quantity (.. % -target -value))}]
          [:button "Add to deck"]
          (let [query (:query state)
                matches (match (get-in state [:deck :identity]) query)]
            (when-not (or (empty? query)
                          (= (:NameEN (first matches)) query))
              (om/set-state! owner :matches matches)
              [:div.typeahead
               (for [i (range (count matches))]
                 [:div {:class (if (= i (:selected state)) "selected" "")
                        :on-click (fn [e] (-> ".deckedit .qty" js/$ .select)
                                    (om/set-state! owner :query (.. e -target -textContent))
                                    (om/set-state! owner :selected i))}
                  (:NameEN (nth matches i))])]))]]))))

(defn deck-collection
  [{:keys [sets decks decks-loaded active-deck]} owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (sab/html
        (cond
          (not decks-loaded) [:h4 "Loading deck collection..."]
          (empty? decks) [:h4 "No decks"]
          :else [:div
                 (for [deck (sort-by :date > decks)]
                   [:div.deckline {:class (when (= active-deck deck) "active")
                                   :on-click #(put! select-channel deck)}
                    [:img {:src (image-url (:identity deck))}]
                    [:div.float-right (deck-status-span sets deck)]
                    [:h4 (:name deck)]
                    [:div.float-right (-> (:date deck) js/Date. js/moment (.format "MMM Do YYYY"))]
                    [:p (get-in deck [:identity :NameEN]) [:br]
                     (when (and (:stats deck) (not= "none" (get-in @app-state [:options :deckstats])))
                       (let [stats (:stats deck)]
                         ; adding key :games to handle legacy stats before adding started vs completed
                         [:span "  Games: " (+ (:games-started stats) (:games stats))
                          " - Completed: " (+ (:games-completed stats) (:games stats))
                          " - Win: " (or (:wins stats) 0)
                          " - Percent Win: " (num->percent (:wins stats) (+ (:wins stats) (:loses stats))) "%"]))]])])))))

(defn line-span
  "Make the view of a single line in the deck - returns a span"
  [sets {:keys [identity cards] :as deck} {:keys [qty card] :as line}]
  [:span qty " "
   (if-let [name (:NameEN card)]
     (let [infaction (noinfcost? identity card)
           banned (banned? card)
           allied (alliance-is-free? cards line)
           valid (and (allowed? card identity)
                      (legal-num-copies? identity line))
           released (released? sets card)
           modqty (if (is-prof-prog? deck card) (- qty 1) qty)]
       [:span
        [:span {:class (cond
                         (and valid released (not banned)) "fake-link"
                         valid "casual"
                         :else "invalid")
                :on-mouse-enter #(put! zoom-channel line)
                :on-mouse-leave #(put! zoom-channel false)} name]
        (card-influence-html card modqty infaction allied)])
     card)])

(defn- create-identity
  [state target-value]
  (let [side (get-in state [:deck :identity :Alignment])
        json-map (.parse js/JSON (.. target-value -target -value))
        id-map (js->clj json-map :keywordize-keys true)
        card (lookup side id-map)]
    (if-let [art (:art id-map)]
      (assoc card :art art)
      card)))

(defn- identity-option-string
  [card]
  (.stringify js/JSON (clj->js {:NameEN (:NameEN card) :id (:code card) :art (:art card)})))

(defn deck-builder
  "Make the deckbuilder view"
  [{:keys [decks decks-loaded sets] :as cursor} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:edit false
       :vs-wizard false
       :vs-minion false
       :vs-fallen false
       :old-deck nil
       :edit-channel (chan)
       :deck nil
       :pool nil
       :resources nil
       :hazards nil
       :sideboard nil
       })

    om/IWillMount
    (will-mount [this]
      (let [edit-channel (om/get-state owner :edit-channel)]
        (go (while true
              (let [card (<! zoom-channel)]
                (om/set-state! owner :zoom card))))
        (go (while true
              (let [edit (<! edit-channel)
                    card (:card edit)
                    max-qty (or (:limited card) 3)
                    cards (om/get-state owner [:deck :cards])
                    match? #(when (= (get-in % [:card :NameEN]) (:NameEN card)) %)
                    existing-line (some match? cards)]
                (let [new-qty (+ (or (:qty existing-line) 0) (:qty edit))
                      rest (remove match? cards)
                      draft-id (is-draft-id? (om/get-state owner [:deck :identity]))
                      new-cards (cond (and (not draft-id) (> new-qty max-qty)) (conj rest {:qty max-qty :card card})
                                      (<= new-qty 0) rest
                                      :else (conj rest {:qty new-qty :card card}))]
                  (om/set-state! owner [:deck :cards] new-cards))
                (deck->str owner)))))
      (go (while true
            (om/set-state! owner :deck (<! select-channel)))))

    om/IRenderState
    (render-state [this state]
      (sab/html
        [:div
         [:div.deckbuilder.blue-shade.panel
          [:div.viewport {:ref "viewport"}
           [:div.decks
            [:div.button-bar
             [:button {:on-click #(new-deck "Hero" owner)} "New Wizard deck"]
             [:button {:on-click #(new-deck "Minion" owner)} "New Minion deck"]
             [:button {:on-click #(new-deck "Balrog" owner)} "New Balrog deck"]
             [:button {:on-click #(new-deck "Fallen-wizard" owner)} "New Fallen deck"]
             [:button {:on-click #(new-deck "Elf-lord" owner)} "New Elf deck"]
             [:button {:on-click #(new-deck "Dwarf-lord" owner)} "New Dwarf deck"]]
            [:div.deck-collection
             (om/build deck-collection {:sets sets :decks decks :decks-loaded decks-loaded :active-deck (om/get-state owner :deck)})]
            [:div {:class (when (:edit state) "edit")}
             (when-let [card (om/get-state owner :zoom)]
               (om/build card-view card))]]

           [:div.decklist
            (when-let [deck (:deck state)]
              (let [identity (:identity deck)
                    cards (:cards deck)
                    edit? (:edit state)
                    delete? (:delete state)]
                [:div
                 (cond
                   edit? [:div.button-bar
                          [:button {:on-click #(save-deck cursor owner)} "Save"]
                          [:button {:on-click #(cancel-edit owner)} "Cancel"]
                          (if (om/get-state owner :vs-wizard)
                            [:button {:on-click #(wizard-edit owner)} "√ vs Wizard"]
                            [:button {:on-click #(wizard-edit owner)} "? vs Wizard"]
                            )
                          (if (om/get-state owner :vs-minion)
                            [:button {:on-click #(minion-edit owner)} "√ vs Minion"]
                            [:button {:on-click #(minion-edit owner)} "? vs Minion"]
                            )
                          (if (om/get-state owner :vs-fallen)
                            [:button {:on-click #(fallen-edit owner)} "√ vs Fallen"]
                            [:button {:on-click #(fallen-edit owner)} "? vs Fallen"]
                            )
                          ]
                   delete? [:div.button-bar
                            [:button {:on-click #(handle-delete cursor owner)} "Confirm Delete"]
                            [:button {:on-click #(end-delete owner)} "Cancel"]]
                   :else [:div.button-bar
                          [:button {:on-click #(edit-deck owner)} "Edit"]
                          [:button {:on-click #(delete-deck owner)} "Delete"]])
                 [:h3 (:name deck)]
                 [:div.header
                  [:img {:src (image-url identity)}]
                  [:h4 {:class (if (released? (:sets @app-state) identity) "fake-link" "casual")
                        :on-mouse-enter #(put! zoom-channel identity)
                        :on-mouse-leave #(put! zoom-channel false)} (:NameEN identity)]
                  (let [count (card-count cards)
                        min-count (min-deck-size identity)]
                    [:div count " cards"
                     (when (< count min-count)
                       [:span.invalid (str " (minimum " min-count ")")])])
                  (let [inf (influence-count deck)
                        mwl (universalinf-count deck)
                        total (+ mwl inf)
                        id-limit (id-inf-limit identity)]
                    [:div "Influence: "
                     ;; we don't use valid? and mwl-legal? functions here, since it concerns influence only
                     [:span {:class (if (> total id-limit) (if (> inf id-limit) "invalid" "casual") "legal")} total]
                     "/" (if (= INFINITY id-limit) "∞" id-limit)
                     (if (pos? total)
                       (list " " (influence-html deck) (restricted-html deck)))])
                  (when (= (:Alignment identity) "Corp")
                    (let [min-point (min-agenda-points deck)
                          points (agenda-points deck)]
                      [:div "Agenda points: " points
                       (when (< points min-point)
                         [:span.invalid " (minimum " min-point ")"])
                       (when (> points (inc min-point))
                         [:span.invalid " (maximum " (inc min-point) ")"])]))
                  [:div (deck-status-span sets deck true true)]]
                 [:div.cards
                  (for [group (sort-by first (group-by #(get-in % [:card :type]) cards))]
                    [:div.group
                     [:h4 (str (or (first group) "Unknown") " (" (card-count (last group)) ")") ]
                     (for [line (sort-by #(get-in % [:card :NameEN]) (last group))]
                       [:div.line
                        (when (:edit state)
                          (let [ch (om/get-state owner :edit-channel)]
                            [:span
                             [:button.small {:on-click #(put! ch {:qty 1 :card (:card line)})
                                             :type "button"} "+"]
                             [:button.small {:on-click #(put! ch {:qty -1 :card (:card line)})
                                             :type "button"} "-"]]))
                        (line-span sets deck line)])])]]))]

           [:div.deckedit
            [:div
             [:p
              [:h3.lftlabel "Deck name"]
              [:h3.rgtlabel "Avatar"]
              [:input.deckname {:type "text" :placeholder "Deck name"
                                :ref "deckname" :value (get-in state [:deck :name])
                                :on-change #(om/set-state! owner [:deck :name] (.. % -target -value))}]]
             [:p
              [:select.identity {:value (get-in state [:deck :identity :NameEN])
                                 :on-change #(om/set-state! owner
                                                            [:deck :identity]
                                                            (lookup (get-in state [:deck :identity :Alignment])
                                                                    (.. % -target -value)))}
               (for [card (sort-by :NameEN (side-identities (get-in state [:deck :identity :Alignment])))]
                 [:option (:NameEN card)])]]
             (om/build card-lookup cursor {:state state})
             [:div
              [:h3.lftlabel "Resources"
               [:span.small "(Type or paste)" ]]
              [:h3.rgtlabel "Hazards"
               [:span.small "(Type or paste)" ]]
              ]

             [:textarea.txttop {:ref "resource-edit" :value (:resource-edit state)
                                :on-change #(handle-edit owner)}]
             [:textarea.txttop {:ref "pool-edit" :value (:pool-edit state)
                                :on-change #(handle-edit-t owner)}]
             [:div
              [:h3.lftlabel "Sideboard"
               [:span.small "(Type or paste)" ]]
              [:h3.rgtlabel "Pool"
               [:span.small "(Type or paste)" ]]
              ]

             [:textarea.txtbot {:ref "hazard-edit" :value (:hazard-edit state)
                                :on-change #(handle-edit-t owner)}]
             [:textarea.txtbot {:ref "sideboard-edit" :value (:sideboard-edit state)
                                :on-change #(handle-edit-t owner)}]
             ]]]]]))))

(go (let [cards (<! cards-channel)
          decks (process-decks (:json (<! (GET (str "/data/decks")))))]
      (load-decks decks)
      (load-alt-arts)
      (>! cards-channel cards)))

(om/root deck-builder app-state {:target (. js/document (getElementById "deckbuilder"))})