(ns game.cards.radicles
  (:require [game.core :refer :all]
            [game.utils :refer :all]
            [game.macros :refer [effect req msg wait-for continue-ability]]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [cardnum.utils :refer [str->int other-side]]
            [cardnum.cards :refer [all-cards]]))

(defn- genetics-trigger?
  "Returns true if Genetics card should trigger - does not work with Adjusted Chronotype"
  [state side event]
  (or (first-event? state side event)
      (and (has-flag? state side :persistent :genetics-trigger-twcharacter)
           (second-event? state side event))))

(defn- shard-constructor
  "Function for constructing a Shard card"
  ([target-locale message effect-fn] (shard-constructor target-locale message nil effect-fn))
  ([target-locale message ability-options effect-fn]
   (letfn [(can-place-shard? [state run] (and run
                                                (= (:locale run) [target-locale])
                                                (zero? (:position run))
                                                (not (:access @state))))]
     {:implementation "Click Shard to place when last Character is passed, but before hitting Successful Run button"
      :abilities [(merge {:effect (effect (discard card {:cause :ability-cost}) (effect-fn eid card target))
                          :msg message}
                         ability-options)]
      :place-cost-bonus (req (when (can-place-shard? state run) [:credit -15 :click -1]))
      :effect (req (when (can-place-shard? state run)
                     (wait-for (register-successful-run state side (:locale run))
                               (do (clear-wait-prompt state :contestant)
                                   (swap! state update-in [:challenger :prompt] rest)
                                   (handle-end-run state side)))))})))

;;; Card definitions
(def card-definitions
  {"Aaron Marrón"
   (let [am {:effect (effect (add-counter card :power 2)
                             (system-msg :challenger (str "places 2 power counters on Aaron Marrón")))}]
     {:abilities [{:counter-cost [:power 1]
                   :msg "remove 1 tag and draw 1 card"
                   :effect (effect (lose-tags 1) (draw))}]
      :events {:agenda-scored am :agenda-stolen am}})

   "Access to Globalsec"
   {:in-play [:link 1]}

   "Activist Support"
   {:events
    {:contestant-turn-begins {:async true
                        :effect (req (if (zero? (:tag challenger))
                                       (do (gain-tags state :challenger eid 1)
                                           (system-msg state :challenger (str "uses " (:title card) " to take 1 tag")))
                                       (effect-completed state :challenger eid)))}
     :challenger-turn-begins {:async true
                          :effect (req (if (not has-bad-pub)
                                         (do (gain-bad-publicity state :contestant eid 1)
                                             (system-msg state :challenger
                                                         (str "uses " (:title card) " to give the contestant 1 bad publicity")))
                                         (effect-completed state :challenger eid)))}}}

   "Adjusted Chronotype"
   {:events {:challenger-loss {:req (req (and (some #{:click} target)
                                          (let [click-losses (count (filter #(= :click %) (mapcat first (turn-events state side :challenger-loss))))]
                                            (or (= 1 click-losses)
                                                (and (= 2 click-losses)
                                                     (has-flag? state side :persistent :genetics-trigger-twcharacter))))))
                           :msg "gain [Click]"
                           :effect (effect (gain :challenger :click 1))}}}

   "Aeneas Informant"
   {:events {:no-discard {:req (req (and (:discard target)
                                       (not= (first (:zone target)) :discard)))
                        :optional {:prompt (msg "Use Aeneas Informant?")
                                   :yes-ability {:msg (msg (str "gain 1 [Credits]"
                                                                (when-not (placed? target)
                                                                  (str " and reveal "  (:title target)))))
                                                 :effect (effect (gain-credits 1))}}}}}

   "Aesops Pawnshop"
   {:flags {:challenger-phase-12 (req (>= (count (all-placed state :challenger)) 2))}
    :abilities [{:effect (req (resolve-ability
                                state side
                                {:msg (msg "discard " (:title target) " and gain 3 [Credits]")
                                 :choices {:req #(and (card-is? % :side :challenger)
                                                      (placed? %)
                                                      (not (card-is? % :cid (:cid card))))}
                                 :effect (effect (gain-credits 3)
                                                 (discard target {:unpreventable true}))}
                                card nil))}]}

   "Akshara Sareen"
   {:in-play [:click 1
              :click-per-turn 1]
    :msg "give each player 1 additional [Click] to spend during their turn"
    :effect (effect (gain :contestant :click-per-turn 1))
    :leave-play (effect (lose :contestant :click-per-turn 1))}

   "Algo Trading"
   {:flags {:challenger-phase-12 (req (pos? (:credit challenger)))}
    :abilities [{:label "Move up to 3 [Credit] from credit pool to Algo Trading"
                 :prompt "Choose how many [Credit] to move" :once :per-turn
                 :choices {:number (req (min (:credit challenger) 3))}
                 :effect (effect (lose-credits target)
                                 (add-counter card :credit target))
                 :msg (msg "move " target " [Credit] to Algo Trading")}
                {:label "Take all credits from Algo Trading"
                 :cost [:click 1]
                 :msg (msg "discard it and gain " (get-counters card :credit) " [Credits]")
                 :effect (effect (gain-credits (get-counters card :credit))
                                 (discard card {:cause :ability-cost}))}]
    :events {:challenger-turn-begins {:req (req (>= (get-counters card :credit) 6))
                                  :effect (effect (add-counter card :credit 2)
                                                  (system-msg (str "adds 2 [Credit] to Algo Trading")))}}}

   "Always Be Running"
   {:implementation "Run requirement not enforced"
    :events {:challenger-turn-begins {:effect (req (toast state :challenger
                                              "Reminder: Always Be Running requires a run on the first click" "info"))}}
    :abilities [{:once :per-turn
                 :cost [:click 2]
                 :msg (msg "break 1 subroutine")}]}

   "All-nighter"
   {:abilities [{:cost [:click 1]
                 :effect (effect (discard card {:cause :ability-cost})
                                 (gain :click 2))
                 :msg "gain [Click][Click]"}]}

   "Angel Arena"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :abilities [{:counter-cost [:power 1]
                 :msg "look at the top card of Stack"
                 :effect (req (when (zero? (get-counters (get-card state card) :power))
                                (discard state :challenger card {:unpreventable true})))
                 :optional {:prompt (msg "Add " (:title (first (:deck challenger))) " to bottom of Stack?")
                            :yes-ability {:msg "add the top card of Stack to the bottom"
                                          :effect (req (move state side (first (:deck challenger)) :deck))}}}]}

   "Armitage Codebusting"
   {:data {:counter {:credit 12}}
    :abilities [{:cost [:click 1]
                 :counter-cost [:credit 2]
                 :msg "gain 2 [Credits]"
                 :effect (req (gain-credits state :challenger 2)
                              (when (zero? (get-counters (get-card state card) :credit))
                                (discard state :challenger card {:unpreventable true})))}]}

   "Artist Colony"
   {:abilities [{:prompt "Choose a card to place"
                 :msg (msg "place " (:title target))
                 :req (req (not (place-locked? state side)))
                 :cost [:forfeit]
                 :choices (req (cancellable (filter #(not (is-type? % "Event")) (:deck challenger)) :sorted))
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (challenger-place target))}]}

   "Assimilator"
   {:abilities [{:label "Turn a facedown card faceup"
                 :cost [:click 2]
                 :prompt "Select a facedown placed card"
                 :choices {:req #(and (facedown? %)
                                      (placed? %)
                                      (= "Challenger" (:side %)))}
                 :effect (req (if (or (is-type? target "Event")
                                      (and (has-subtype? target "Console")
                                           (some #(has-subtype? % "Console") (all-active-placed state :challenger))))
                                ;; Consoles and events are immediately unpreventably discarded.
                                (discard state side target {:unpreventable true})
                                ;; Other cards are moved to rig and have events wired.
                                (flip-faceup state side target)))
                 :msg (msg "turn " (:title target) " faceup")}]}

   "Bhagat"
   {:events {:successful-run {:req (req (and (= target :hq)
                                             (first-successful-run-on-locale? state :hq)))
                              :msg "force the Contestant to discard the top card of R&D"
                              :effect (effect (mill :contestant))}}}

   "Bank Job"
   {:data {:counter {:credit 8}}
    :events {:successful-run
             {:silent (req true)
              :req (req (is-party? (:locale run)))
              :effect (req (let [bj (get-card state card)]
                             (when-not (:replace-access (get-in @state [:run :run-effect]))
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:effect (req (if (> (count (filter #(= (:title %) "Bank Job") (all-active-placed state :challenger))) 1)
                                                      (resolve-ability state side
                                                        {:prompt "Select a copy of Bank Job to use"
                                                         :choices {:req #(and (placed? %) (= (:title %) "Bank Job"))}
                                                         :effect (req (let [c target
                                                                            creds (get-counters (get-card state c) :credit)]
                                                                        (resolve-ability state side
                                                                          {:prompt "How many Bank Job credits?"
                                                                           :choices {:number (req (get-counters (get-card state c) :credit))}
                                                                           :msg (msg "gain " target " [Credits]")
                                                                           :effect (req (gain-credits state side target)
                                                                                        (set-prop state side c :counter {:credit (- creds target)})
                                                                                        (when (not (pos? (get-counters (get-card state c) :credit)))
                                                                                          (discard state side c {:unpreventable true})))}
                                                                         card nil)))}
                                                       bj nil)
                                                      (resolve-ability state side
                                                        {:prompt "How many Bank Job credits?"
                                                         :choices {:number (req (get-counters (get-card state card) :credit))}
                                                         :msg (msg "gain " target " [Credits]")
                                                         :effect (req (let [creds (get-counters (get-card state card) :credit)]
                                                                        (gain-credits state side target)
                                                                        (set-prop state side card :counter {:credit (- creds target)})
                                                                        (when (not (pos? (get-counters (get-card state card) :credit)))
                                                                          (discard state side card {:unpreventable true}))))}
                                                       bj nil)))}))))}}}

   "Bazaar"
   (letfn [(hazard-and-in-hand? [target challenger]
             (and (is-type? target "Hazard")
                  (some #(= (:title %) (:title target)) (:hand challenger))))]
     {:events
      {:challenger-place
       {:interactive (req (hazard-and-in-hand? target challenger))
        :silent (req (not (hazard-and-in-hand? target challenger)))
        :async true
        :req (req (and (is-type? target "Hazard") (= [:hand] (:previous-zone target))))
        :effect (req (let [hw (:title target)]
                       (continue-ability state side
                                         {:optional {:req (req (some #(when (= (:title %) hw) %) (:hand challenger)))
                                                     :prompt (msg "Place another copy of " hw "?")
                                                     :msg (msg "place another copy of " hw)
                                                     :yes-ability {:async true
                                                                   :effect (req (if-let [c (some #(when (= (:title %) hw) %)
                                                                                                 (:hand challenger))]
                                                                                  (challenger-place state side eid c nil)))}}} card nil)))}}})

   "Beach Party"
   {:in-play [:hand-size 5]
    :events {:challenger-turn-begins {:msg "lose [Click]"
                                  :effect (effect (lose :click 1))}}}

   "Beth Kilrain-Chang"
   (let [ability {:once :per-turn
                  :label "Gain 1 [Credits], draw 1 card, or gain [Click] (start of turn)"
                  :req (req (:challenger-phase-12 @state))
                  :effect (req (let [c (:credit contestant)
                                     b (:title card)]
                                 (cond
                                   ;; gain 1 credit
                                   (<= 5 c 9)
                                   (do (gain-credits state side 1)
                                       (system-msg state side (str "uses " b " to gain 1 [Credits]")))
                                   ;; draw 1 card
                                   (<= 10 c 14)
                                   (do (draw state side 1)
                                       (system-msg state side (str "uses " b " to draw 1 card")))
                                   ;; gain 1 click
                                   (<= 15 c)
                                   (do (gain state side :click 1)
                                       (system-msg state side (str "uses " b " to gain [Click]"))))))}]
     {:flags {:drip-economy true}
      :abilities [ability]
      :events {:challenger-turn-begins ability}})

   "Biometric Spoofing"
   {:interactions {:prevent [{:type #{:net :brain :meat}
                              :req (req true)}]}
    :abilities [{:label "[Discard]: Prevent 2 damage"
                 :msg "prevent 2 damage"
                 :effect (effect (discard card {:cause :ability-cost})
                                 (damage-prevent :brain 2)
                                 (damage-prevent :net 2)
                                 (damage-prevent :meat 2))}]}

   "Bio-Modeled Network"
   {:interactions {:prevent [{:type #{:net}
                              :req (req true)}]}
    :events {:pre-damage {:req (req (= target :net))
                          :effect (effect (update! (assoc card :dmg-amount (nth targets 2))))}}
    :abilities [{:msg (msg "prevent " (dec (:dmg-amount card)) " net damage")
                 :effect (effect (damage-prevent :net (dec (:dmg-amount card)))
                                 (discard card {:cause :ability-cost}))}]}

   "Blockade Challenger"
   {:abilities [{:cost [:click 2]
                 :msg "draw 3 cards and shuffle 1 card from their Grip back into their Stack"
                 :effect (effect (draw 3)
                                 (resolve-ability
                                   {:prompt "Choose a card in your Grip to shuffle back into your Stack"
                                    :choices {:req #(and (in-hand? %)
                                                         (= (:side %) "Challenger"))}
                                    :effect (effect (move target :deck)
                                                    (shuffle! :deck))}
                                  card nil))}]}

   "Bloo Moose"
   {:flags {:challenger-phase-12 (req true)}
    :abilities [{:req (req (and (:challenger-phase-12 @state)
                                (not (seq (get-in @state [:challenger :locked :discard])))))
                 :once :per-turn
                 :prompt "Choose a card in the Heap to remove from the game and gain 2 [Credits]"
                 :show-discard true
                 :choices {:req #(and (in-discard? %) (= (:side %) "Challenger"))}
                 :msg (msg "remove " (:title target) " from the game and gain 2 [Credits]")
                 :effect (effect (gain-credits 2)
                                 (move target :rfg))}]}

   "Borrowed Satellite"
   {:in-play [:hand-size 1 :link 1]}

   "Bug Out Bag"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :events {:challenger-turn-ends {:req (req (zero? (count (:hand challenger))))
                                :msg (msg "draw " (get-counters card :power) " cards. Bug Out Bag is discarded")
                                :effect (effect (draw (get-counters card :power))
                                                (discard card))}}}

   "Caldera"
   {:interactions {:prevent [{:type #{:net :brain}
                              :req (req true)}]}
    :abilities [{:cost [:credit 3]
                 :msg "prevent 1 net damage"
                 :effect (effect (damage-prevent :net 1))}
                {:cost [:credit 3]
                 :msg "prevent 1 brain damage"
                 :effect (effect (damage-prevent :brain 1))}]}

   "Charlatan"
   {:abilities [{:cost [:click 2]
                 :label "Make a run"
                 :prompt "Choose a locale"
                 :choices (req runnable-locales)
                 :msg (msg "make a run on " target)
                 :effect (effect (run target nil card))}
                {:label "Pay credits equal to strength of approached revealed Character to bypass it"
                 :once :per-run
                 :req (req (and (:run @state) (revealed? current-character)))
                 :msg (msg "pay " (:current-strength current-character) " [Credits] and bypass " (:title current-character))
                 :effect (effect (pay :challenger card :credit (:current-strength current-character)))}]}

   "Chatterjee University"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:cost [:click 1]
                 :label "Place a resource from your Grip"
                 :prompt "Select a resource to place from your Grip"
                 :choices {:req #(and (is-type? % "Resource") (in-hand? %))}
                 :msg (msg "place " (:title target))
                 :effect (req (place-cost-bonus state side [:credit (- (get-counters card :power))])
                              (challenger-place state side target)
                              (when (pos? (get-counters card :power))
                                (add-counter state side card :power -1)))}]}

   "Chrome Parlor"
   {:events
    {:pre-damage {:req (req (has-subtype? (second targets) "Cybernetic"))
                  :effect (effect (damage-prevent target Integer/MAX_VALUE))}}}

   "Citadel Sanctuary"
   {:interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :abilities [{:label "[Discard] and discard all cards in Grip to prevent all meat damage"
                 :msg "discard all cards in their Grip and prevent all meat damage"
                 :effect (req (discard state side card {:cause :ability-cost})
                              (doseq [c (:hand challenger)]
                                (discard state side c {:unpreventable true}))
                              (damage-prevent state side :meat Integer/MAX_VALUE))}]
    :events {:challenger-turn-ends
             {:req (req (pos? (:tag challenger)))
              :msg "force the Contestant to initiate a trace"
              :label "Trace 1 - If unsuccessful, Challenger removes 1 tag"
              :trace {:base 1
                      :unsuccessful {:msg "remove 1 tag"
                                     :async true
                                     :effect (effect (lose-tags :challenger eid 1))}}}}}

   "Clan Vengeance"
   {:events {:pre-resolve-damage {:req (req (pos? (last targets)))
                                  :effect (effect (add-counter card :power 1)
                                                  (system-msg :challenger (str "places 1 power counter on Clan Vengeance")))}}
    :abilities [{:label "[Discard]: Discard 1 random card from HQ for each power counter"
                 :req (req (pos? (get-counters card :power)))
                 :msg (msg "discard " (min (get-counters card :power) (count (:hand contestant))) " cards from HQ")
                 :effect (effect (discard-cards (take (min (get-counters card :power) (count (:hand contestant)))
                                              (shuffle (:hand contestant))))
                                 (discard card {:cause :ability-cost}))}]}

   "Compromised Employee"
   {:recurring 1
    :events {:reveal {:req (req (character? target))
                   :msg "gain 1 [Credits]"
                   :effect (effect (gain-credits :challenger 1))}}}

   "Contestantorate Defector"
   {:events {:contestant-click-draw {:msg (msg "reveal " (-> target first :title))}}}

   "Councilman"
   {:implementation "Does not restrict Challenger to Site / Region just revealed"
    :events {:reveal {:req (req (and (#{"Site" "Region"} (:type target))
                                  (can-pay? state :challenger nil [:credit (reveal-cost state :contestant target)])))
                   :effect (req (toast state :challenger (str "Click Councilman to hide " (card-str state target {:visible true})
                                                          " that was just revealed") "info")
                                (toast state :contestant (str "Challenger has the opportunity to hide with Councilman.") "error"))}}
    :abilities [{:prompt "Select an site or region that was just revealed"
                 :choices {:req #(and (revealed? %)
                                      (or (is-type? % "Site") (is-type? % "Region")))}
                 :effect (req (let [c target
                                    creds (reveal-cost state :contestant c)]
                                (when (can-pay? state side nil [:credit creds])
                                  (resolve-ability
                                    state :challenger
                                    {:msg (msg "pay " creds " [Credit] and hide " (:title c) ". Councilman is discarded")
                                     :effect (req (lose-credits state :challenger creds)
                                                  (hide state :contestant c)
                                                  (register-turn-flag!
                                                    state side card :can-reveal
                                                    (fn [state side card]
                                                      (if (= (:cid card) (:cid c))
                                                        ((constantly false)
                                                         (toast state :contestant "Cannot reveal the rest of this turn due to Councilman"))
                                                        true)))
                                                  (discard state side card {:unpreventable true}))}
                                   card nil))))}]}

   "Counter Surveillance"
   {:implementation "Does not prevent access of cards placed in the root of a locale"
    :abilities [{:cost [:click 1]
                 :makes-run true
                 :prompt "Choose a locale to run with Counter Surveillance"
                 :msg (msg "run " target " and discards Counter Surveillance")
                 :choices (req (cancellable runnable-locales))
                 :effect (req (discard state side card {:cause :ability-cost})
                              (game.core/run state side target nil card)
                              (register-events state side
                                               {:successful-run
                                                {:silent (req true)
                                                 :effect (req (if (>= (:credit challenger) (:tag challenger))
                                                                ;; Can pay, do access
                                                                (do (system-msg state side (str "uses Counter Surveillance to access up to "
                                                                                                (:tag challenger) " cards by paying "
                                                                                                (:tag challenger) " [Credit]"))
                                                                    (pay state side card :credit (:tag challenger))
                                                                    (access-bonus state side (- (:tag challenger) 1)))
                                                                ;; Can't pay, don't access cards
                                                                (do (system-msg state side "could not afford to use Counter Surveillance")
                                                                    ;; Cannot access any cards
                                                                    (max-access state side 0))))}
                                                :run-ends {:effect (effect (unregister-events card))}}
                                               (assoc card :zone '(:discard))))}]
    :events {:successful-run nil :run-ends nil}}

   "Crash Space"
   {:interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :recurring 2
    :abilities [{:label "Discard to prevent up to 3 meat damage"
                 :msg "prevent up to 3 meat damage"
                 :effect (effect (discard card {:cause :ability-cost}) (damage-prevent :meat 3))}]}

   "Crypt"
   {:events {:successful-run
             {:silent (req true)
              :req (req (= :archives target))
              :optional {:prompt "Place a virus counter on Crypt?"
                         :yes-ability {:effect (effect (add-counter card :virus 1)
                                                       (system-msg "places a virus counter on Crypt"))}}}}
    :abilities [{:label "[Click][Discard]: place a virus resource from the stack"
                 :prompt "Choose a virus"
                 :msg (msg "place " (:title target) " from the stack")
                 :choices (req (cancellable (filter #(and (is-type? % "Resource")
                                                          (has-subtype? % "Virus"))
                                                    (:deck challenger)) :sorted))
                 :cost [:click 1]
                 :counter-cost [:virus 3]
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (challenger-place target)
                                 (discard card {:cause :ability-cost}))}]}

   "Dadiana Chacon"
   (let [discardme {:effect (effect (system-msg "discards Dadiana Chacon and suffers 3 meat damage")
                                  (register-events {:play {:req (req (= "Challenger" (:side target)))
                                                           :effect (effect (unregister-events card)
                                                                           (damage eid :meat 3 {:unboostable true :card card})
                                                                           (discard card {:cause :ability-cost}))}} card))}
         ability {:once :per-turn
                  :msg "gain 1 [Credits]"
                  :req (req (< (get-in @state [:challenger :credit]) 6))
                  :effect (req (gain-credits state :challenger 1))}]
     {:effect (req (if (zero? (get-in @state [:challenger :credit]))
                     (resolve-ability state side discardme card nil)
                     (add-watch state :dadiana
                                (fn [k ref old new]
                                  (when (and (not (zero? (get-in old [:challenger :credit])))
                                             (zero? (get-in new [:challenger :credit])))
                                    (resolve-ability ref side discardme card nil))))))
      :leave-play (req (remove-watch state :dadiana))
      :flags {:drip-economy true}
      :events {:play nil
               :challenger-turn-begins ability}})

   "Daily Casts"
   (let [ability {:once :per-turn
                  :label "Take 2 [Credits] (start of turn)"
                  :msg "gain 2 [Credits]"
                  :req (req (:challenger-phase-12 @state))
                  :counter-cost [:credit 2]
                  :effect (req (gain-credits state :challenger 2)
                               (when (zero? (get-counters (get-card state card) :credit))
                                 (discard state :challenger card {:unpreventable true})))}]
   {:data {:counter {:credit 8}}
    :flags {:drip-economy true}
    :abilities [ability]
    :events {:challenger-turn-begins ability}})

   "Data Dealer"
   {:abilities [{:cost [:click 1 :forfeit] :effect (effect (gain-credits 9))
                 :msg (msg "gain 9 [Credits]")}]}

   "Data Folding"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :msg "gain 1 [Credits]"
                  :once :per-turn
                  :req (req (and (>= (available-mu state) 2) (:challenger-phase-12 @state)))
                  :effect (effect (gain-credits 1))}]
    {:flags {:drip-economy true}
    :abilities [ability]
    :events {:challenger-turn-begins ability}})

   "Data Leak Reversal"
   {:req (req (some #{:hq :rd :archives} (:successful-run challenger-reg)))
    :abilities [{:req (req tagged) :cost [:click 1] :effect (effect (mill :contestant))
                 :msg "force the Contestant to discard the top card of R&D"}]}

   "DDoS"
   {:abilities [{:msg "prevent the contestant from revealing the outermost piece of character during a run on any locale this turn"
                 :effect (effect
                           (register-turn-flag!
                             card :can-reveal
                             (fn [state side card]
                               (if (and (character? card)
                                        (= (count (get-in @state (concat [:contestant :locales] (:locale (:run @state)) [:characters])))
                                           (inc (character-index state card))))
                                 ((constantly false) (toast state :contestant "Cannot reveal any outermost Character due to DDoS." "warning"))
                                 true)))
                           (discard card {:cause :ability-cost}))}]}

   "Dean Lister"
   {:abilities [{:req (req (:run @state))
                 :msg (msg "add +1 strength for each card in their Grip to " (:title target) " until the end of the run")
                 :choices {:req #(and (has-subtype? % "Icebreaker")
                                      (placed? %))}
                 :effect (effect (update! (assoc card :dean-target target))
                                 (discard (get-card state card) {:cause :ability-cost})
                                 (update-breaker-strength target))}]
    :events {:run-ends nil :pre-breaker-strength nil}
    :discard-effect {:effect
                   (effect (register-events
                             (let [dean {:effect (effect (unregister-events card)
                                                         (update! (dissoc card :dean-target))
                                                         (update-breaker-strength (:dean-target card)))}]
                               {:run-ends dean
                                :pre-breaker-strength {:req (req (= (:cid target)(:cid (:dean-target card))))
                                                       :effect (effect (breaker-strength-bonus (count (:hand challenger))))}}) card))}}

   "Decoy"
   {:interactions {:prevent [{:type #{:tag}
                              :req (req true)}]}
    :abilities [{:msg "avoid 1 tag" :effect (effect (tag-prevent :challenger 1) (discard card {:cause :ability-cost}))}]}

   "District 99"
   {:implementation "Adding power counters must be done manually for resources/hazard discarded manually (e.g. by being over MU)"
    :abilities [{:label "Add a card from your heap to your grip"
                 :req (req (seq (filter #(= (:faction (:identity challenger)) (:faction %)) (:discard challenger))))
                 :counter-cost [:power 3] :cost [:click 1]
                 :prompt (msg "Which card to add to grip?")
                 :choices (req (filter #(= (:faction (:identity challenger)) (:faction %)) (:discard challenger)))
                 :effect (effect (move target :hand))
                 :msg (msg "Add " (:title target) " to grip")}
                {:label "Add a power counter manually"
                 :once :per-turn
                 :effect (effect (add-counter card :power 1))
                 :msg (msg "manually add a power counter.")}]
    :events (let [prog-or-hw (fn [t] (or (resource? (first t)) (hazard? (first t))))
                  discard-event (fn [side-discard] {:once :per-turn
                                                :req (req (first-event? state side side-discard prog-or-hw))
                                                :effect (effect (add-counter card :power 1))})]
              {:contestant-discard (discard-event :contestant-discard)
               :challenger-discard (discard-event :challenger-discard)})}

   "DJ Fenris"
   (let [is-draft-id? #(.startsWith (:code %) "00")
         can-host? (fn [challenger c] (and (is-type? c "Identity")
                                       (has-subtype? c "g-mod")
                                       (not= (-> challenger :identity :faction) (:faction c))
                                       (not (is-draft-id? c))))
         fenris-effect {:prompt "Choose a g-mod identity to host on DJ Fenris"
                        :choices (req (cancellable (filter (partial can-host? challenger) (vals @all-cards)) :sorted))
                        :msg (msg "host " (:title target))
                        :effect (req (let [card (assoc-host-zones card)
                                           ;; Work around for get-card and update!
                                           c (assoc target :type "Fake-Identity")
                                           c (make-card c)
                                           c (assoc c :host (dissoc card :hosted)
                                                      :zone '(:onhost)
                                                      ;; semi hack to get deactivate to work
                                                      :placed true)]

                                       ;; Manually host id on card
                                       (update! state side (assoc card :hosted [c]))
                                       (card-init state :challenger c)

                                       (clear-wait-prompt state :contestant)
                                       (effect-completed state side eid)))}]
     {:async true
      :effect (req (show-wait-prompt state :contestant "Challenger to pick identity to host on DJ Fenris")
                   (continue-ability state side fenris-effect card nil))})

   "Donut Taganes"
   {:msg "increase the play cost of operations and events by 1 [Credits]"
    :events {:pre-play-instant
             {:effect (effect (play-cost-bonus [:credit 1]))}}}

   "Dr. Lovegood"
   {:flags {:challenger-phase-12 (req (> (count (all-placed state :challenger)) 1))}
    :abilities [{:req (req (:challenger-phase-12 @state))
                 :prompt "Select an placed card to make its text box blank for the remainder of the turn"
                 :once :per-turn
                 :choices {:req placed?}
                 :msg (msg "make the text box of " (:title target) " blank for the remainder of the turn")
                 :effect (req (let [c target]
                                (disable-card state side c)
                                (register-events state side
                                                 {:post-challenger-turn-ends
                                                  {:effect (req (enable-card state side (get-card state c))
                                                                (when-let [reactivate-effect (:reactivate (card-def c))]
                                                                  (resolve-ability state :challenger reactivate-effect
                                                                                   (get-card state c) nil))
                                                                (unregister-events state side card))}} card)))}]
    :events {:post-challenger-turn-ends nil}}

   "Drug Dealer"
   {:flags {:challenger-phase-12 (req (some #(card-flag? % :drip-economy true) (all-active-placed state :challenger)))}
    :abilities [{:label "Lose 1 [Credits] (start of turn)"
                 :msg (msg (if (zero? (get-in @state [:challenger :credit]))
                             "lose 0 [Credits] (challenger has no credits to lose)"
                             "lose 1 [Credits]"))
                 :req (req (:challenger-phase-12 @state))
                 :once :per-turn
                 :effect (effect (lose-credits 1))}]
    :events {:contestant-turn-begins {:msg (msg "draw " (if (zero? (count (get-in @state [:challenger :deck])))
                                                    "0 cards (challenger's stack is empty)"
                                                    "1 card"))
                                :effect (effect (draw :challenger 1))}
             :challenger-turn-begins {:msg (msg "lose " (if (zero? (get-in @state [:challenger :credit]))
                                                      "0 [Credits] (challenger has no credits to lose)"
                                                      "1 [Credits]"))
                                  :once :per-turn
                                  :effect (effect (lose-credits 1))}}}

   "Duggars"
   {:abilities [{:cost [:click 4] :effect (effect (draw 10)) :msg "draw 10 cards"}]}

   "Dummy Box"
   (letfn [(dummy-prevent [type] {:msg (str "prevent a " type " from being discarded")
                                  :async true
                                  :priority 15
                                  :prompt (str "Choose a " type " in your Grip")
                                  :choices {:req #(and (is-type? % (capitalize type))
                                                       (in-hand? %))}
                                  :effect (effect (move target :discard)
                                                  (discard-prevent (keyword type) 1))})]
     {:interactions {:prevent [{:type #{:discard-hazard :discard-radicle :discard-resource}
                                :req (req (not= :purge (:cause target)))}]}
      :abilities [(dummy-prevent "hazard")
                  (dummy-prevent "radicle")
                  (dummy-prevent "resource")]})

   "Earthrise Hotel"
   (let [ability {:msg "draw 2 cards"
                  :once :per-turn
                  :counter-cost [:power 1]
                  :req (req (:challenger-phase-12 @state))
                  :effect (req (draw state :challenger 2)
                               (when (zero? (get-counters (get-card state card) :power))
                                 (discard state :challenger card {:unpreventable true})))}]
   {:flags {:challenger-turn-draw true
            :challenger-phase-12 (req (< 1 (count (filter #(card-flag? % :challenger-turn-draw true)
                                                      (cons (get-in @state [:challenger :identity])
                                                            (all-active-placed state :challenger))))))}
    :data {:counter {:power  3}}
    :events {:challenger-turn-begins ability}
    :abilities [ability]})

   "Eden Shard"
   (shard-constructor :rd "force the Contestant to draw 2 cards" (req (draw state :contestant 2)))

   "Emptied Mind"
   (let [ability {:req (req (zero? (count (:hand challenger))))
                  :msg "gain [Click]"
                  :label "Gain [Click] (start of turn)"
                  :once :per-turn
                  :effect (effect (gain :click 1))}]
     {:events {:challenger-turn-begins ability}
      :abilities [ability]})

   "Enhanced Vision"
   {:events {:successful-run {:silent (req true)
                              :msg (msg "force the Contestant to reveal " (:title (first (shuffle (:hand contestant)))))
                              :req (req (genetics-trigger? state side :successful-run))}}}

   "Fall Guy"
   {:interactions {:prevent [{:type #{:discard-radicle}
                              :req (req true)}]}
    :abilities [{:label "[Discard]: Prevent another placed radicle from being discarded"
                 :effect (effect (discard card {:unpreventable true :cause :ability-cost})
                                 (discard-prevent :radicle 1))}
                {:label "[Discard]: Gain 2 [Credits]"
                 :msg "gain 2 [Credits]"
                 :effect (effect (discard card {:cause :ability-cost})
                                 (gain-credits 2))}]}

   "Fan Site"
   {:events {:agenda-scored {:msg "add it to their score area as an agenda worth 0 agenda points"
                             :async true
                             :req (req (placed? card))
                             :effect (req (as-agenda state :challenger eid card 0))}}}

   "Fester"
   {:events {:purge {:msg "force the Contestant to lose 2 [Credits] if able"
                     :effect (effect (pay :contestant card :credit 2))}}}

   "Film Critic"
   (letfn [(get-agenda [card] (first (filter #(= "Agenda" (:type %)) (:hosted card))))
           (host-agenda? [agenda]
             {:optional {:prompt (str "You access " (:title agenda) ". Host it on Film Critic?")
                        :yes-ability {:effect (req (host state side card (move state side agenda :play-area))
                                                   (access-end state side eid agenda)
                                                   (when-not (:run @state)
                                                     (swap! state dissoc :access)))
                                      :msg (msg "host " (:title agenda) " instead of accessing it")}}})]
     {:events {:access {:req (req (and (empty? (filter #(= "Agenda" (:type %)) (:hosted card)))
                                       (is-type? target "Agenda")))
                        :interactive (req true)
                        :async true
                        :effect (effect (continue-ability (host-agenda? target) card nil))}}
      :abilities [{:cost [:click 2] :label "Add hosted agenda to your score area"
                   :req (req (get-agenda card))
                   :async true
                   :effect (req (let [c (get-agenda card)
                                      points (get-agenda-points state :challenger c)]
                                  (as-agenda state :challenger eid c points)))
                   :msg (msg (let [c (get-agenda card)]
                               (str "add " (:title c) " to their score area and gain "
                                    (quantify (get-agenda-points state :challenger c) "agenda point"))))}]})

   "Find the Truth"
   {:events {:post-challenger-draw {:msg (msg "reveal that they drew: "
                                          (join ", " (map :title (get-in @state [:challenger :register :most-recent-drawn]))))}
             :successful-run {:interactive (req true)
                              :optional {:req (req (and (first-event? state side :successful-run)
                                                        (-> @state :contestant :deck count pos?)))
                                         :prompt "Use Find the Truth to look at the top card of R&D?"
                                         :yes-ability {:prompt (req (->> contestant :deck first :title (str "The top card of R&D is ")))
                                                       :msg "look at the top card of R&D"
                                                       :choices ["OK"]}}}}}

   "First Responders"
   {:abilities [{:cost [:credit 2]
                 :req (req (some #(= (:side %) "Contestant") (map second (turn-events state :challenger :damage))))
                 :msg "draw 1 card"
                 :effect (effect (draw))}]}

   "Gang Sign"
   {:events {:agenda-scored
             {:async true
              :interactive (req true)
              :msg (msg "access " (quantify (get-in @state [:challenger :hq-access]) "card") " from HQ")
              :effect (req (wait-for
                             ; manually trigger the pre-access event to alert Nerve Agent.
                             (trigger-event-sync state side :pre-access :hq)
                             (let [from-hq (access-count state side :hq-access)]
                               (continue-ability
                                 state :challenger
                                 (access-helper-hq
                                   state from-hq
                                   ; access-helper-hq uses a set to keep track of which cards have already
                                   ; been accessed. by adding HQ root's contents to this set, we make the challenger
                                   ; unable to access those cards, as Gang Sign intends.
                                   (set (get-in @state [:contestant :locales :hq :content])))
                                 card nil))))}}}

   "Gbahali"
   {:abilities [{:label "[Discard]: Break the last subroutine on the encountered piece of character"
                 :req (req (and (:run @state) (revealed? current-character)))
                 :effect (effect (discard card {:cause :ability-cost})
                                 (system-msg :challenger
                                             (str "discards Gbahali to break the last subroutine on "
                                                  (:title current-character))))}]}

   "Gene Conditioning Shoppe"
   {:msg "make Genetics trigger a second time each turn"
    :effect (effect (register-persistent-flag! card :genetics-trigger-twcharacter (constantly true)))
    :leave-play (effect (clear-persistent-flag! card :genetics-trigger-twcharacter))}

   "Ghost Challenger"
   {:data {:counter {:credit 3}}
    :abilities [{:counter-cost [:credit 1]
                 :msg "gain 1 [Credits]"
                 :req (req (:run @state))
                 :effect (req (gain-credits state side 1)
                              (trigger-event state side :spent-stealth-credit card)
                              (when (zero? (get-counters (get-card state card) :credit))
                                (discard state :challenger card {:unpreventable true})))}]}

   "Globalsec Security Clearance"
   {:req (req (> (:link challenger) 1))
    :flags {:challenger-phase-12 (req true)}
    :abilities [{:msg "lose [Click] and look at the top card of R&D"
                 :once :per-turn
                 :effect (effect (prompt! card (str "The top card of R&D is "
                                                    (:title (first (:deck contestant)))) ["OK"] {}))}]
    :events {:challenger-turn-begins {:req (req (get-in @state [:per-turn (:cid card)]))
                                  :effect (effect (lose :click 1))}}}

   "Grifter"
   {:events {:challenger-turn-ends
             {:effect (req (let [ab (if (get-in @state [:challenger :register :successful-run])
                                      {:effect (effect (gain-credits 1)) :msg "gain 1 [Credits]"}
                                      {:effect (effect (discard card)) :msg "discard Grifter"})]
                             (resolve-ability state side ab card targets)))}}}

   "Guru Davinder"
   {:flags {:cannot-pay-net-damage true}
    :events {:pre-damage
             {:req    (req (and (or (= target :meat) (= target :net))
                                (pos? (last targets))))
              :msg (msg "prevent all " (if (= target :meat) "meat" "net") " damage")
              :effect (req (damage-prevent state side :meat Integer/MAX_VALUE)
                           (damage-prevent state side :net Integer/MAX_VALUE)
                           (if (< (:credit challenger) 4)
                             (discard state side card)
                             (resolve-ability
                               state :challenger
                               {:optional
                                {:prompt "Pay 4 [Credits] to prevent discarding Guru Davinder?"
                                 :player :challenger
                                 :yes-ability {:effect (effect (lose-credits :challenger 4)
                                                               (system-msg (str "pays 4 [Credits] to prevent Guru Davinder "
                                                                                "from being discarded")))}
                                 :no-ability {:effect (effect (discard card))}}}
                              card nil)))}}}

   "Hades Shard"
   (shard-constructor :archives "access all cards in Archives" {:async true}
                      (req (discard state side card {:cause :ability-cost})
                           (swap! state update-in [:contestant :discard] #(map (fn [c] (assoc c :seen true)) %))
                           (wait-for (trigger-event-sync state side :pre-access :archives)
                                     (resolve-ability state :challenger
                                                      (choose-access (get-in @state [:contestant :discard])
                                                                     '(:archives) {:no-root true}) card nil))))

   "Hard at Work"
   (let [ability {:msg "gain 2 [Credits] and lose [Click]"
                  :once :per-turn
                  :effect (effect (lose :click 1) (gain-credits 2))}]
   {:flags {:drip-economy true}
    :events {:challenger-turn-begins ability}
    :abilities [ability]})

   "Human First"
   {:events {:agenda-scored {:msg (msg "gain " (get-agenda-points state :contestant target) " [Credits]")
                             :effect (effect (gain-credits :challenger (get-agenda-points state :contestant target)))}
             :agenda-stolen {:msg (msg "gain " (get-agenda-points state :challenger target) " [Credits]")
                             :effect (effect (gain-credits (get-agenda-points state :challenger target)))}}}

   "Hunting Grounds"
   {:abilities [{:label "Prevent a \"when encountered\" ability on a piece of Character"
                 :msg "prevent a \"when encountered\" ability on a piece of Character"
                 :once :per-turn}
                 {:label "[Discard]: Place the top 3 cards of your Stack facedown"
                  :msg "place the top 3 cards of their Stack facedown"
                  :effect (req (discard state side card {:cause :ability-cost})
                               (doseq [c (take 3 (get-in @state [:challenger :deck]))]
                                 (challenger-place state side c {:facedown true})))}]}

   "Ice Analyzer"
   {:implementation "Credit use restriction is not enforced"
    :events {:reveal {:req (req (character? target))
                   :msg "place 1 [Credits] on Ice Analyzer"
                   :effect (effect (add-counter :challenger card :credit 1))}}
    :abilities [{:counter-cost [:credit 1]
                 :effect (effect (gain-credits 1))
                 :msg "take 1 [Credits] to place resources"}]}

   "Ice Carver"
   {:events {:pre-character-strength
             {:req (req (and (= (:cid target) (:cid current-character)) (:revealed target)))
              :effect (effect (character-strength-bonus -1 target))}}}

   "Inside Man"
   {:recurring 2}

   "Investigative Journalism"
   {:req (req has-bad-pub)
    :abilities [{:cost [:click 4] :msg "give the Contestant 1 bad publicity"
                 :effect (effect (gain-bad-publicity :contestant 1)
                                 (discard card {:cause :ability-cost}))}]}

   "Jackpot!"
   (let [jackpot {:interactive (req true)
                  :async true
                  :req (req (= :challenger (:as-agenda-side target)))
                  :effect (req (show-wait-prompt state :contestant "Challenger to use Jackpot!")
                               (continue-ability
                                 state side
                                 {:optional
                                  {:prompt "Discard Jackpot!?"
                                   :no-ability {:effect (effect (clear-wait-prompt :contestant))}
                                   :yes-ability
                                   {:prompt "Choose how many [Credit] to take"
                                    :choices {:number (req (get-counters card :credit))}
                                    :async true
                                    :effect (req (gain-credits state :challenger target)
                                                 (system-msg state :challenger (str "discards Jackpot! to gain " target " credits"))
                                                 (clear-wait-prompt state :contestant)
                                                 (discard state :challenger eid card nil))}}}
                                 card nil))}]
     {:events
      {:challenger-turn-begins {:effect (effect (add-counter :challenger card :credit 1))}
       :agenda-stolen (dissoc jackpot :req)
       :as-agenda jackpot}})

   "Jak Sinclair"
   (let [ability {:label "Make a run (start of turn)"
                  :prompt "Choose a locale to run with Jak Sinclair"
                  :once :per-turn
                  :req (req (:challenger-phase-12 @state))
                  :choices (req runnable-locales)
                  :msg (msg "make a run on " target " during which no resources can be used")
                  :makes-run true
                  :effect (effect (run target))}]
   {:implementation "Doesn't prevent resource use"
    :flags {:challenger-phase-12 (req true)}
    :place-cost-bonus (req [:credit (- (:link challenger))])
    :events {:challenger-turn-begins
              {:optional {:req (req (not (get-in @state [:per-turn (:cid card)])))
                          :prompt "Use Jak Sinclair to make a run?"
                          :yes-ability ability}}}
    :abilities [ability]})

   "Jarogniew Mercs"
   {:effect (effect (gain-tags :challenger eid 1)
                    (add-counter card :power (-> @state :challenger :tag (+ 3))))
    :flags {:undiscardable-while-radicles true}
    :interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :abilities [{:label "Prevent 1 meat damage"
                 :counter-cost [:power 1]
                 :effect (req (damage-prevent state side :meat 1)
                              (when (zero? (get-counters (get-card state card) :power))
                                (discard state :challenger card {:unpreventable true})))}]}

   "John Masanori"
   {:events {:successful-run {:req (req (= 1 (count (get-in @state [:challenger :register :successful-run]))))
                              :msg "draw 1 card" :once-key :john-masanori-draw
                              :effect (effect (draw))}
             :unsuccessful-run {:req (req (= 1 (count (get-in @state [:challenger :register :unsuccessful-run]))))
                                :async true
                                :msg "take 1 tag" :once-key :john-masanori-tag
                                :effect (effect (gain-tags :challenger eid 1))}}}

   "Joshua B."
   (let [ability {:msg "gain [Click]"
                  :once :per-turn
                  :label "Gain [Click] (start of turn)"
                  :effect (effect (gain :click 1))
                  :end-turn {:async true
                             :effect (effect (gain-tags eid 1))
                             :msg "gain 1 tag"}}]
     {:flags {:challenger-phase-12 (req true)}
      :events {:challenger-turn-begins
               {:optional {:prompt "Use Joshua B. to gain [Click]?"
                           :once :per-turn
                           :yes-ability ability}}}
      :abilities [ability]})

   "Kati Jones"
   {:abilities [{:cost [:click 1]
                 :msg "store 3 [Credits]"
                 :once :per-turn
                 :effect (effect (add-counter card :credit 3))}
                {:cost [:click 1]
                 :msg (msg "gain " (get-counters card :credit) " [Credits]")
                 :once :per-turn
                 :label "Take all credits"
                 :effect (req (gain-credits state side (get-counters card :credit))
                              (add-counter state side card :credit (- (get-counters card :credit))))}]}

 "Kasi String"
 {:events {:run-ends {:req (req (and (first-event? state :challenger :run-ends is-party?)
                                     (not (get-in @state [:run :did-steal]))
                                     (get-in @state [:run :did-access])
                                     (is-party? (:locale run))))
                      :effect (effect (add-counter card :power 1))
                      :msg "add a power counter to itself"}
           :counter-added {:req (req (>= (get-counters (get-card state card) :power) 4))
                           :effect (effect (as-agenda :challenger card 1))
                           :msg "add it to their score area as an agenda worth 1 agenda point"}}}

   "Keros Mcintyre"
   {:events
    {:hide
     {:req (req (and (first-event? state side :hide)
                     (= (second targets) :challenger)))
      :once :per-turn
      :msg "gain 2 [Credits]"
      :effect (effect (gain-credits 2))}}}

   "Kongamato"
   {:abilities [{:label "[Discard]: Break the first subroutine on the encountered piece of character"
                 :req (req (and (:run @state) (revealed? current-character)))
                 :effect (effect (discard card {:cause :ability-cost})
                                 (system-msg :challenger
                                             (str "discards Kongamato to break the first subroutine on "
                                                  (:title current-character))))}]}

   "Laguna Velasco District"
   {:events {:challenger-click-draw {:msg "draw 1 card" :effect (effect (draw))}}}

   "Lewi Guilherme"
   (let [ability {:once :per-turn
                  :optional {:once :per-turn
                             :prompt "Pay 1 [Credits] to keep Lewi Guilherme?"
                             :yes-ability {:effect (req (if (pos? (:credit challenger))
                                                          (do (lose-credits state side 1)
                                                              (system-msg state side "pays 1 [Credits] to keep Lewi Guilherme"))
                                                          (do (discard state side card)
                                                              (system-msg state side "must discard Lewi Guilherme"))))}
                             :no-ability {:effect (effect (discard card)
                                                          (system-msg "chooses to discard Lewi Guilherme"))}}}]
   {:flags {:drip-economy true ;; for Drug Dealer
            :challenger-phase-12 (req (< 1 (count (filter #(card-flag? % :drip-economy true)
                                                      (all-active-placed state :challenger)))))}

    ;; KNOWN ISSUE: :effect is not fired when Assimilator turns cards over or Dr. Lovegood re-enables it.
    :effect (effect (lose :contestant :hand-size 1))
    :leave-play (effect (gain :contestant :hand-size 1))
    :abilities [(assoc-in ability [:req] (req (:challenger-phase-12 @state)))]
    :events {:challenger-turn-begins ability}})

   "Levy Advanced Research Lab"
   (letfn [(lab-keep [cards]
             {:prompt "Choose a Resource to keep"
              :choices (cons "None" (filter #(= "Resource" (:type %)) cards))
              :async true
              :msg (msg (if (= target "None") "take no card to their Grip" (str "take " (-> target :title) " to their Grip")))
              :effect (req (when (not= target "None")
                             (move state side target :hand))
                           (if (not-empty cards)
                             (let [tobottom (remove #(= % target) cards)]
                               (continue-ability state side (reorder-choice :challenger :contestant tobottom '()
                                                                            (count tobottom) tobottom "bottom") card nil))
                             (do (clear-wait-prompt state :contestant)
                                 (effect-completed state side eid))))})]
   {:abilities [{:cost [:click 1]
                 :msg (msg "draw 4 cards: " (join ", " (map :title (take 4 (:deck challenger)))))
                 :async true
                 :effect (req (show-wait-prompt state :contestant "Challenger to choose card to keep")
                              (let [from (take 4 (:deck challenger))]
                                (continue-ability state side (lab-keep from) card nil)))}]})

   "Liberated Account"
   {:data {:counter {:credit 16}}
    :abilities [{:cost [:click 1]
                 :counter-cost [:credit 4]
                 :msg "gain 4 [Credits]"
                 :effect (req (gain-credits state :challenger 4)
                              (when (zero? (get-counters (get-card state card) :credit))
                                (discard state :challenger card {:unpreventable true})))}]}

   "Liberated Chela"
   {:abilities [{:cost [:click 5 :forfeit]
                 :msg "add it to their score area"
                 :async true
                 :effect (req (if (not (empty? (:scored contestant)))
                                (do (show-wait-prompt state :challenger "Contestant to decide whether or not to prevent Liberated Chela")
                                    (continue-ability
                                      state side
                                      {:prompt (msg "Forfeit an agenda to prevent Liberated Chela from being added to Challenger's score area?")
                                       :choices ["Yes" "No"]
                                       :player :contestant
                                       :async true
                                       :effect (effect (continue-ability
                                                         (if (= target "Yes")
                                                           {:player :contestant
                                                            :prompt "Select an agenda to forfeit"
                                                            :choices {:req #(in-contestant-scored? state side %)}
                                                            :effect (effect (forfeit target)
                                                                            (move :challenger card :rfg)
                                                                            (clear-wait-prompt :challenger))}
                                                           {:async true
                                                            :effect (req (clear-wait-prompt state :challenger)
                                                                         (as-agenda state :challenger eid card 2))
                                                            :msg "add it to their score area as an agenda worth 2 points"})
                                                         card nil))} card nil))
                                (continue-ability
                                  state side
                                  {:async true
                                   :effect (req (as-agenda state :challenger eid card 2))
                                   :msg "add it to their score area as an agenda worth 2 points"} card nil)))}]}

   "Logic Bomb"
   {:implementation "Bypass effect is manual"
    :abilities [{:label "Bypass the encountered character"
                 :req (req (and (:run @state)
                                (revealed? current-character)))
                 :msg (msg "bypass "
                           (:title current-character)
                           (when (pos? (:click challenger))
                             (str " and loses "
                                  (apply str (repeat (:click challenger) "[Click]")))))
                 :effect (effect (discard card {:cause :ability-cost})
                                 (lose :click (:click challenger)))}]}

   "London Library"
   {:abilities [{:label "Place a non-virus resource on London Library"
                 :cost [:click 1]
                 :prompt "Select a non-virus resource to place on London Library from your grip"
                 :choices {:req #(and (is-type? % "Resource")
                                      (not (has-subtype? % "Virus"))
                                      (in-hand? %))}
                 :msg (msg "host " (:title target))
                 :effect (effect (challenger-place target {:host-card card :no-cost true}))}
                {:label "Add a resource hosted on London Library to your Grip"
                 :cost [:click 1]
                 :choices {:req #(:host %)} ;TODO: this seems to allow all hosted cards to be bounced
                 :msg (msg "add " (:title target) " to their Grip")
                 :effect (effect (move target :hand))}]
    :events {:challenger-turn-ends {:effect (req (doseq [c (:hosted card)]
                                               (when (is-type? c "Resource")
                                                 (discard state side c))))}}}

   "Maxwell James"
   {:in-play [:link 1]
    :abilities [{:req (req (some #{:hq} (:successful-run challenger-reg)))
                 :prompt "Choose a piece of Character protecting a party locale"
                 :choices {:req #(and (character? %) (revealed? %) (is-party? (second (:zone %))))}
                 :msg "hide a piece of Character protecting a party locale"
                 :effect (effect (hide target)
                                 (discard card {:cause :ability-cost}))}]}

   "Miss Bones"
   {:data {:counter {:credit 12}}
    :implementation "Credit use restriction not enforced"
    :abilities [{:counter-cost [:credit 1]
                 :msg "gain 1 [Credits] for discarding placed cards"
                 :async true
                 :effect (req (take-credits state :challenger 1)
                              (if (zero? (get-counters (get-card state card) :credit))
                                (discard state :challenger eid card {:unpreventable true})
                                (effect-completed state :challenger eid)))}]}

   "Motivation"
   (let [ability {:msg "look at the top card of their Stack"
                  :label "Look at the top card of Stack (start of turn)"
                  :once :per-turn
                  :req (req (:challenger-phase-12 @state))
                  :effect (effect (prompt! card (str "The top card of your Stack is "
                                                     (:title (first (:deck challenger)))) ["OK"] {}))}]
   {:flags {:challenger-turn-draw true
            :challenger-phase-12 (req (some #(card-flag? % :challenger-turn-draw true) (all-active-placed state :challenger)))}
    :events {:challenger-turn-begins ability}
    :abilities [ability]})

   "Mr. Li"
   {:abilities [{:cost [:click 1]
                 :msg (msg "draw 2 cards")
                 :effect (req (draw state side 2)
                              (let [drawn (get-in @state [:challenger :register :most-recent-drawn])]
                                (resolve-ability
                                  state side
                                  {:prompt "Select 1 card to add to the bottom of the Stack"
                                   :choices {:req #(and (in-hand? %)
                                                        (some (fn [c] (= (:cid c) (:cid %))) drawn))}
                                   :msg (msg "add 1 card to the bottom of the Stack")
                                   :effect (req (move state side target :deck))} card nil)))}]}

   "Muertos Gang Member"
   {:effect (req (resolve-ability
                   state :contestant
                   {:prompt "Select a card to hide"
                    :choices {:req #(and (= (:side %) "Contestant")
                                         (not (is-type? % "Agenda"))
                                         (:revealed %))}
                    :effect (req (hide state side target))}
                  card nil))
    :leave-play (req (resolve-ability
                       state :contestant
                       {:prompt "Select a card to reveal, ignoring the reveal cost"
                        :choices {:req #(not (:revealed %))}
                        :effect (req (reveal state side target {:ignore-cost :reveal-cost})
                                     (system-msg state side (str "reveals " (:title target) " at no cost")))}
                      card nil))
    :abilities [{:msg "draw 1 card"
                 :effect (effect (discard card {:cause :ability-cost}) (draw))}]}

   "Net Mercur"
   {:abilities [{:counter-cost [:credit 1]
                 :msg "gain 1 [Credits]"
                 :effect (effect (gain-credits 1)
                                 (trigger-event :spent-stealth-credit card))}]
    :events {:spent-stealth-credit
             {:req (req (and (:run @state)
                             (has-subtype? target "Stealth")))
              :once :per-run
              :async true
              :effect (effect (show-wait-prompt :contestant "Challenger to use Net Mercur")
                              (continue-ability
                                {:prompt "Place 1 [Credits] on Net Mercur or draw 1 card?"
                                 :player :challenger
                                 :choices ["Place 1 [Credits]" "Draw 1 card"]
                                 :effect (req (if (= target "Draw 1 card")
                                                (do (draw state side)
                                                    (clear-wait-prompt state :contestant)
                                                    (system-msg state :challenger (str "uses Net Mercur to draw 1 card")))
                                                (do (add-counter state :challenger card :credit 1)
                                                    (clear-wait-prompt state :contestant)
                                                    (system-msg state :challenger (str "places 1 [Credits] on Net Mercur")))))}
                               card nil))}}}

   "Network Exchange"
   {:msg "increase the place cost of non-innermost Character by 1"
    :events {:pre-contestant-place {:req (req (is-type? target "Character"))
                                :effect (req (when (pos? (count (:dest-zone (second targets))))
                                               (place-cost-bonus state :contestant [:credit 1])))}}}

   "Neutralize All Threats"
   {:in-play [:hq-access 1]
    :events {:pre-access {:req (req (and (= target :archives)
                                         (seq (filter #(:discard %) (:discard contestant)))))
                          :effect (req (swap! state assoc-in [:per-turn (:cid card)] true))}
             :access {:effect (req (swap! state assoc-in [:challenger :register :force-discard] false))}
             :pre-discard {:req (req (let [cards (map first (turn-events state side :pre-discard))]
                                     (and (empty? (filter #(:discard %) cards))
                                          (number? (:discard target)))))
                         :once :per-turn
                         :effect (req (swap! state assoc-in [:challenger :register :force-discard] true))}}}

   "New Angeles City Hall"
   {:interactions {:prevent [{:type #{:tag}
                              :req (req true)}]}
    :events {:agenda-stolen {:msg "discard itself"
                             :effect (effect (discard card))}}
    :abilities [{:cost [:credit 2]
                 :msg "avoid 1 tag"
                 :effect (effect (tag-prevent :challenger 1))}]}

   "No One Home"
   (letfn [(first-chance? [state side]
             (< (+ (event-count state side :pre-tag)
                   (event-count state side :pre-damage))
                2))
           (start-trace [type]
             (let [message (str "avoid any " (if (= type :net)
                                               "amount of net damage"
                                               "number of tags"))]
               {:player :contestant
                :label (str "Trace 0 - if unsuccessful, " message)
                :trace {:base 0
                        :priority 11
                        :unsuccessful {:msg message
                                       :effect (req (if (= type :net)
                                                      (damage-prevent state side :net Integer/MAX_VALUE)
                                                      (tag-prevent state :challenger Integer/MAX_VALUE)))}}}))]
     {:interactions {:prevent [{:type #{:net :tag}
                                :req (req (first-chance? state side))}]}
      :abilities [{:msg "force the Contestant to trace"
                   :async true
                   :effect (req (let [type (get-in @state [:prevent :current])]
                                  (wait-for (discard state side card {:unpreventable true})
                                            (continue-ability state side (start-trace type)
                                                              card nil))))}]})

   "Off-Campus Apartment"
   {:flags {:challenger-place-draw true}
    :abilities [{:label "Place and host a connection on Off-Campus Apartment"
                 :effect (effect (resolve-ability
                                   {:cost [:click 1]
                                    :prompt "Select a connection in your Grip to place on Off-Campus Apartment"
                                    :choices {:req #(and (has-subtype? % "Connection")
                                                         (can-pay? state side nil :credit (:cost %))
                                                         (in-hand? %))}
                                    :msg (msg "host " (:title target) " and draw 1 card")
                                    :effect (effect (challenger-place target {:host-card card}))}
                                  card nil))}
                {:label "Host an placed connection"
                 :prompt "Select a connection to host on Off-Campus Apartment"
                 :choices {:req #(and (has-subtype? % "Connection")
                                      (placed? %))}
                 :msg (msg "host " (:title target) " and draw 1 card")
                 :effect (effect (host card target) (draw))}]
    :events {:challenger-place {:req (req (= (:cid card) (:cid (:host target))))
                              :effect (effect (draw))}}}

   "Offcharacterr Frank"
   {:abilities [{:cost [:credit 1]
                 :req (req (some #(= :meat %) (map first (turn-events state :challenger :damage))))
                 :msg "force the Contestant to discard 2 random cards from HQ"
                 :effect (effect (discard-cards :contestant (take 2 (shuffle (:hand contestant))))
                                 (discard card {:cause :ability-cost}))}]}

   "Oracle May"
   {:abilities [{:cost [:click 1]
                 :once :per-turn
                 :prompt "Choose card type"
                 :choices ["Event" "Hazard" "Resource" "Radicle"]
                 :effect (req (let [c (first (get-in @state [:challenger :deck]))]
                                (system-msg state side (str "spends [Click] to use Oracle May, names " target
                                                            " and reveals " (:title c)))
                                (if (is-type? c target)
                                  (do (system-msg state side (str "gains 2 [Credits] and draws " (:title c)))
                                      (gain-credits state side 2) (draw state side))
                                  (do (system-msg state side (str "discards " (:title c))) (mill state side)))))}]}

   "Order of Sol"
   {:effect (req (add-watch state :order-of-sol
                            (fn [k ref old new]
                              (when (and (not (zero? (get-in old [:challenger :credit])))
                                         (zero? (get-in new [:challenger :credit])))
                                (resolve-ability ref side {:msg "gain 1 [Credits]"
                                                           :once :per-turn
                                                           :effect (effect (gain-credits 1))}
                                                 card nil)))))
    :events {:challenger-turn-begins {:req (req (zero? (:credit challenger)))
                                  :msg "gain 1 [Credits]"
                                  :effect (req (gain-credits state :challenger 1)
                                               (swap! state assoc-in [:per-turn (:cid card)] true))}
             :contestant-turn-begins {:req (req (zero? (:credit challenger)))
                                :msg "gain 1 [Credits]"
                                :effect (req (gain-credits state :challenger 1)
                                             (swap! state assoc-in [:per-turn (:cid card)] true))}
             :challenger-place {:silent (req (pos? (:credit challenger)))
                              :req (req (and (= target card)
                                             (zero? (:credit challenger))))
                              :msg "gain 1 [Credits]"
                              :effect (req (gain-credits state :challenger 1)
                                           (swap! state assoc-in [:per-turn (:cid card)] true))}}
    :leave-play (req (remove-watch state :order-of-sol))}

   "PAD Tap"
   {:events {:contestant-credit-gain
             {:req (req (and (not= target :contestant-click-credit)
                             (= 1 (->> (turn-events state :contestant :contestant-credit-gain)
                                       (remove #(= (first %) :contestant-click-credit))
                                       count))))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :challenger 1))}}
    :contestant-abilities [{:label "Discard PAD Tap"
                      :cost [:credit 3 :click 1]
                      :req (req (= :contestant side))
                      :effect (effect (system-msg :contestant "spends [Click] and 3 [Credits] to discard PAD Tap")
                                      (discard :contestant card))}]}

   "Paige Piper"
   (letfn [(pphelper [title cards]
             {:optional
              {:prompt (str "Use Paige Piper to discard copies of " title "?")
               :yes-ability {:prompt "How many would you like to discard?"
                             :choices (take (inc (count cards)) ["0" "1" "2" "3" "4" "5"])
                             :msg "shuffle their Stack"
                             :effect (req (let [target (str->int target)]
                                            (trigger-event state side :searched-stack nil)
                                            (shuffle! state :challenger :deck)
                                            (doseq [c (take target cards)]
                                              (discard state side c {:unpreventable true}))
                                            (when (pos? target)
                                              (system-msg state side (str "discards "
                                                                          (quantify target "cop" "y" "ies")
                                                                          " of " title)))))}}})]
     {:events {:challenger-place {:req (req (first-event? state side :challenger-place))
                                :async true
                                :effect (effect (continue-ability
                                                 (pphelper (:title target)
                                                           (->> (:deck challenger)
                                                                (filter #(has? % :title (:title target)))
                                                                (vec)))
                                                 card nil))}}})
   "Patron"
   (let [ability {:prompt "Choose a locale for Patron" :choices (req (conj locales "No locale"))
                  :req (req (and (not (click-spent? :challenger state)) (not (used-this-turn? (:cid card) state))))
                  :msg (msg "target " target)
                  :effect (req (when (not= target "No locale")
                                 (update! state side (assoc card :locale-target target))))}]
     {:events {:challenger-turn-begins ability
               :successful-run
               {:req (req (= (zone->name (get-in @state [:run :locale])) (:locale-target (get-card state card))))
                :once :per-turn
                :effect (req (let [st card]
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:mandatory true
                                       :effect (effect (resolve-ability
                                                         {:msg "draw 2 cards instead of accessing"
                                                          :effect (effect (draw 2)
                                                                          (update! (dissoc st :locale-target)))}
                                                         st nil))})))}
               :challenger-turn-ends {:effect (effect (update! (dissoc card :locale-target)))}}
      :abilities [ability]})

   "Paparazzi"
   {:effect (req (swap! state update-in [:challenger :tagged] inc))
    :events {:pre-damage {:req (req (= target :meat)) :msg "prevent all meat damage"
                          :effect (effect (damage-prevent :meat Integer/MAX_VALUE))}}
    :leave-play (req (swap! state update-in [:challenger :tagged] dec))}

   "Personal Workshop"
   (let [remove-counter
         {:req (req (not (empty? (:hosted card))))
          :once :per-turn
          :msg (msg "remove 1 counter from " (:title target))
          :choices {:req #(:host %)}
          :effect (req (if (zero? (get-counters (get-card state target) :power))
                         (challenger-place state side (dissoc target :counter) {:no-cost true})
                         (add-counter state side target :power -1)))}]
     {:flags {:drip-economy true}
      :abilities [{:label "Host a resource or piece of hazard" :cost [:click 1]
                   :prompt "Select a card to host on Personal Workshop"
                   :choices {:req #(and (#{"Resource" "Hazard"} (:type %))
                                        (in-hand? %)
                                        (= (:side %) "Challenger"))}
                   :effect (req (if (zero? (:cost target))
                                  (challenger-place state side target)
                                  (host state side card
                                        (assoc target :counter {:power (:cost target)}))))
                   :msg (msg "host " (:title target) "")}
                  (assoc remove-counter
                    :label "Remove 1 counter from a hosted card (start of turn)"
                    :cost [:credit 1])
                  {:label "X[Credit]: Remove counters from a hosted card"
                   :choices {:req #(:host %)}
                   :req (req (not (empty? (:hosted card))))
                   :effect (req (let [paydowntarget target
                                      num-counters (get-counters (get-card state paydowntarget) :power)]
                                  (resolve-ability
                                    state side
                                    {:prompt "How many counters to remove?"
                                     :choices {:number (req (min (:credit challenger)
                                                                 num-counters))}
                                     :msg (msg "remove " target " counters from " (:title paydowntarget))
                                     :effect (req (do
                                                    (lose-credits state side target)
                                                    (if (= num-counters target)
                                                      (challenger-place state side (dissoc paydowntarget :counter) {:no-cost true})
                                                      (add-counter state side paydowntarget :power (- target)))))}
                                    card nil)))}]
      :events {:challenger-turn-begins remove-counter}})

   "Political Operative"
   {:req (req (some #{:hq} (:successful-run challenger-reg)))
    :abilities [{:prompt "Select a revealed card with a discard cost"
                 :choices {:req #(and (:discard %)
                                      (revealed? %))}
                 :effect (req (let [cost (modified-discard-cost state :challenger target)]
                                (when (can-pay? state side nil [:credit cost])
                                  (resolve-ability
                                    state side
                                    {:msg (msg "pay " cost " [Credit] and discard " (:title target))
                                     :effect (effect (lose-credits cost)
                                                     (discard card {:cause :ability-cost})
                                                     (discard target))}
                                    card targets))))}]}

   "Power Tap"
   {:events {:pre-init-trace {:msg "gain 1 [Credits]"
                              :effect (effect (gain-credits :challenger 1))}}}

   "Professional Contacts"
   {:abilities [{:cost [:click 1]
                 :msg "gain 1 [Credits] and draw 1 card"
                 :effect (effect (gain-credits 1)
                                 (draw))}]}

   "Psych Mike"
   {:events {:successful-run-ends
             {:req (req (and (= [:rd] (:locale target))
                             (first-event? state side :successful-run-ends)))
              :effect (effect (gain-credits :challenger (total-cards-accessed target :deck)))}}}

   "Public Sympathy"
   {:in-play [:hand-size 2]}

   "Rachel Beckman"
   {:in-play [:click 1 :click-per-turn 1]
    :events {:challenger-gain-tag {:effect (effect (discard card {:unpreventable true}))
                               :msg (msg "discards Rachel Beckman for being tagged")}}
    :effect (req (when tagged
                   (discard state :challenger card {:unpreventable true})))
    :reactivate {:effect (req (when tagged
                                (discard state :challenger card {:unpreventable true})))}}

   "Raymond Flint"
   {:effect (req (add-watch state :raymond-flint
                            (fn [k ref old new]
                              (when (< (get-in old [:contestant :bad-publicity]) (get-in new [:contestant :bad-publicity]))
                                (wait-for
                                  ; manually trigger the pre-access event to alert Nerve Agent.
                                  (trigger-event-sync ref side :pre-access :hq)
                                  (let [from-hq (access-count state side :hq-access)]
                                    (resolve-ability
                                      ref side
                                      (access-helper-hq
                                        state from-hq
                                        ; see note in Gang Sign
                                        (set (get-in @state [:contestant :locales :hq :content])))
                                      card nil)))))))
    :leave-play (req (remove-watch state :raymond-flint))
    :abilities [{:msg "expose 1 card"
                 :choices {:req placed?}
                 :async true
                 :effect (effect (expose eid target) (discard card {:cause :ability-cost}))}]}

   "Reclaim"
   {:abilities
    [{:label "Place a resource, piece of hazard, or virtual radicle from your Heap"
      :cost [:click 1]
      :req (req (not-empty (:hand challenger)))
      :prompt "Choose a card to discard"
      :choices (req (cancellable (:hand challenger) :sorted))
      :async true
      :effect (req (wait-for
                     (discard state :challenger card {:cause :ability-cost})
                     (wait-for
                       (discard state :challenger target {:unpreventable true})
                       (continue-ability
                         state :challenger
                         {:prompt "Choose a card to place"
                          :choices (req (cancellable
                                          (filter #(and (or (is-type? % "Resource")
                                                            (is-type? % "Hazard")
                                                            (and (is-type? % "Radicle")
                                                                 (has-subtype? % "Virtual")))
                                                        (can-pay? state :challenger nil (:cost %)))
                                                  (:discard challenger))
                                          :sorted))
                          :msg (msg "place " (:title target) " from the Heap")
                          :async true
                          :effect (req (challenger-place state :challenger eid target nil))}
                         card nil))))}]}

   "Rolodex"
   {:async true
    :msg "look at the top 5 cards of their Stack"
    :effect (req (show-wait-prompt state :contestant "Challenger to rearrange the top cards of their Stack")
                 (let [from (take 5 (:deck challenger))]
                   (if (pos? (count from))
                     (continue-ability
                       state side
                       (reorder-choice :challenger :contestant from '() (count from) from)
                       card nil)
                     (do (clear-wait-prompt state :contestant)
                         (effect-completed state side eid)))))
    :discard-effect {:effect (effect (system-msg :challenger
                                               (str "discards "
                                                    (join ", " (map :title (take 3 (:deck challenger))))
                                                    " from their Stack due to Rolodex being discarded"))
                                   (mill :challenger 3))}}

   "Rosetta 2.0"
   {:abilities [{:req (req (and (not (place-locked? state side))
                                (some #(is-type? % "Resource") (all-active-placed state :challenger))))
                 :cost [:click 1]
                 :prompt "Choose an placed resource to remove from the game"
                 :choices {:req #(and (placed? %) (is-type? % "Resource"))}
                 :effect (req (let [n (:cost target)
                                    t (:title target)]
                                (move state side target :rfg)
                                (resolve-ability state side
                                  {:prompt "Choose a non-virus resource to place"
                                   :msg (req (if (not= target "No place")
                                               (str "remove " t
                                                    " from the game and place " (:title target)
                                                    ", lowering its cost by " n)
                                               (str "shuffle their Stack")))
                                   :priority true
                                   :choices (req (cancellable
                                                   (conj (vec (sort-by :title (filter #(and (is-type? % "Resource")
                                                                                            (not (has-subtype? % "Virus")))
                                                                                      (:deck challenger))))
                                                         "No place")))
                                   :effect (req (trigger-event state side :searched-stack nil)
                                                (shuffle! state side :deck)
                                                (when (not= target "No place")
                                                  (place-cost-bonus state side [:credit (- n)])
                                                  (challenger-place state side target)))} card nil)))}]}

   "Rogue Trading"
   {:data {:counter {:credit 18}}
    :abilities [{:cost [:click 2]
                 :counter-cost [:credit 6]
                 :msg "gain 6 [Credits] and take 1 tag"
                 :effect (req (gain-credits state :challenger 6)
                              (when (zero? (get-counters (get-card state card) :credit))
                                (discard state :challenger card {:unpreventable true}))
                              (gain-tags state :challenger eid 1))}]}

   "Sacrificial Clone"
   {:interactions {:prevent [{:type #{:net :brain :meat}
                              :req (req true)}]}
    :abilities [{:effect (req (doseq [c (concat (get-in challenger [:rig :hazard])
                                                (filter #(not (has-subtype? % "Virtual"))
                                                        (get-in challenger [:rig :radicle]))
                                                (:hand challenger))]
                                (discard state side c {:cause :ability-cost}))
                              (lose-credits state side :all)
                              (lose-tags state side :all)
                              (lose state side :run-credit :all)
                              (damage-prevent state side :net Integer/MAX_VALUE)
                              (damage-prevent state side :meat Integer/MAX_VALUE)
                              (damage-prevent state side :brain Integer/MAX_VALUE))}]}

   "Sacrificial Construct"
   {:interactions {:prevent [{:type #{:discard-resource :discard-hazard}
                              :req (req true)}]}
    :abilities [{:effect (effect (discard-prevent :resource 1) (discard-prevent :hazard 1)
                                 (discard card {:cause :ability-cost}))}]}

   "Safety First"
   {:in-play [:hand-size {:mod -2}]
    :events {:challenger-turn-ends
             {:async true
              :effect (req (if (< (count (:hand challenger)) (hand-size state :challenger))
                             (do (system-msg state :challenger (str "uses " (:title card) " to draw a card"))
                                 (draw state :challenger eid 1 nil))
                             (effect-completed state :challenger eid)))}}}

   "Salvaged Vanadis Armory"
   {:events {:damage
             {:effect (req (show-wait-prompt state :contestant "Challenger to use Salvaged Vanadis Armory")
                           (resolve-ability
                             state :challenger
                             {:optional
                              {:prompt "Use Salvaged Vanadis Armory?"
                               :yes-ability {:msg (msg "force the Contestant to discard the top "
                                                       (get-turn-damage state :challenger)
                                                       " cards of R&D and discard itself")
                                             :effect (effect (mill :contestant (get-turn-damage state :challenger))
                                                             (clear-wait-prompt :contestant)
                                                             (discard card {:unpreventable true}))}
                               :no-ability {:effect (effect (clear-wait-prompt :contestant))}}}
                                            card nil))}}}

   "Salsette Slums"
   {:flags {:slow-discard (req true)}
    :implementation "Will not trigger Maw when used on card already discarded (2nd ability)"
    :events {:challenger-place
             {:req (req (= card target))
              :silent (req true)
              :effect (effect (update! (assoc card :slums-active true)))}
             :challenger-turn-begins
             {:effect (effect (update! (assoc card :slums-active true)))}
             :pre-discard
             {:req (req (and (:slums-active card)
                             (-> target card-def :flags :must-discard not)
                             (:discard target)
                             (= (:side target) "Contestant")))
              :effect (req (toast state :challenger (str "Click Salsette Slums to remove " (:title target)
                                                     " from the game") "info" {:prevent-duplicates true}))}}
    :abilities [{:label "Remove the currently accessed card from the game instead of discarding it"
                 :req (req (let [c (:card (first (get-in @state [:challenger :prompt])))]
                             (if-let [discard-cost (discard-cost state side c)]
                               (if (can-pay? state :challenger nil :credit discard-cost)
                                 (if (:slums-active card)
                                   true
                                   ((toast state :challenger "Can only use a copy of Salsette Slums once per turn.") false))
                                 ((toast state :challenger (str "Unable to pay for " (:title c) ".")) false))
                               ((toast state :challenger "Not currently accessing a card with a discard cost.") false))))
                 :msg (msg (let [c (:card (first (get-in @state [:challenger :prompt])))]
                             (str "pay " (discard-cost state side c) " [Credits] and remove " (:title c) " from the game")))
                 :effect (req (let [c (:card (first (get-in @state [:challenger :prompt])))]
                                (deactivate state side c)
                                (move state :contestant c :rfg)
                                (pay state :challenger card :credit (discard-cost state side c))
                                (trigger-event state side :no-discard c)
                                (update! state side (dissoc card :slums-active))
                                (close-access-prompt state side)
                                (when-not (:run @state)
                                  (swap! state dissoc :access))))}
                {:label "Remove a card discarded this turn from the game"
                 :req (req (if (:slums-active card)
                             true
                             ((toast state :challenger "Can only use a copy of Salsette Slums once per turn.") false)))
                 :effect (effect (resolve-ability
                                   {; only allow targeting cards that were discarded this turn -- not perfect, but good enough?
                                    :choices {:req #(some (fn [c] (= (:cid %) (:cid c)))
                                                          (map first (turn-events state side :challenger-discard)))}
                                    :msg (msg "remove " (:title target) " from the game")
                                    :effect (req (deactivate state side target)
                                                 (trigger-event state side :no-discard target)
                                                 (move state :contestant target :rfg)
                                                 (update! state side (dissoc card :slums-active)))}
                                   card nil))}]}

   "Same Old Thing"
   {:abilities [{:cost [:click 2]
                 :req (req (and (not (seq (get-in @state [:challenger :locked :discard])))
                                (pos? (count (filter #(is-type? % "Event") (:discard challenger))))))
                 :prompt "Select an event to play"
                 :msg (msg "play " (:title target))
                 :show-discard true
                 :choices {:req #(and (is-type? % "Event")
                                      (= (:zone %) [:discard]))}
                 :effect (effect (discard card {:cause :ability-cost}) (play-instant target))}]}

   "Scrubber"
   {:recurring 2}

   "Security Testing"
   (let [ability {:prompt "Choose a locale for Security Testing"
                  :choices (req (conj locales "No locale"))
                  :msg (msg "target " target)
                  :req (req (and (not (click-spent? :challenger state))
                                 (not (used-this-turn? (:cid card) state))))
                  :effect (req (when (not= target "No locale")
                                 (update! state side (assoc card :locale-target target))))}]
     {:events {:challenger-turn-begins ability
               :successful-run
               {:req (req (= (zone->name (get-in @state [:run :locale]))
                             (:locale-target (get-card state card))))
                :once :per-turn
                :effect (req (let [st card]
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:mandatory true
                                       :effect (effect (resolve-ability
                                                         {:msg "gain 2 [Credits] instead of accessing"
                                                          :effect (effect (gain-credits 2)
                                                                          (update! (dissoc st :locale-target)))}
                                                         st nil))})))}
               :challenger-turn-ends {:effect (effect (update! (dissoc card :locale-target)))}}
      :abilities [ability]})

   "Slipstream"
    {:implementation "Use Slipstream before hitting Continue to pass current character"
     :abilities [{:req (req (:run @state))
                  :effect (req (let [character-pos  (get-in @state [:run :position])]
                                 (resolve-ability state side
                                   {:prompt (msg "Choose a piece of Character protecting a central locale at the same position as " (:title current-character))
                                    :choices {:req #(and (is-central? (second (:zone %)))
                                                         (character? %)
                                                         (= character-pos (inc (character-index state %))))}
                                    :msg (msg "approach " (card-str state target))
                                    :effect (req (let [dest (second (:zone target))]
                                                   (swap! state update-in [:run]
                                                          #(assoc % :position character-pos :locale [dest]))
                                                   (discard state side card)))}
                                card nil)))}]}

   "Spoilers"
   {:events {:agenda-scored {:interactive (req true)
                             :msg "discard the top card of R&D"
                             :effect (effect (mill :contestant))}}}

   "Starlight Crusade Funding"
   {:msg "ignore additional costs on Double events"
    :effect (req (swap! state assoc-in [:challenger :register :double-ignore-additional] true))
    :events {:challenger-turn-begins
             {:msg "lose [Click] and ignore additional costs on Double events"
              :effect (req (lose state :challenger :click 1)
                           (swap! state assoc-in [:challenger :register :double-ignore-additional] true))}}
    :leave-play (req (swap! state update-in [:challenger :register] dissoc :double-ignore-additional))}

   "Stim Dealer"
   {:events {:challenger-turn-begins
             {:effect (req (if (>= (get-counters card :power) 2)
                             (do (add-counter state side card :power (- (get-counters card :power)))
                                 (damage state side eid :brain 1 {:unpreventable true :card card})
                                 (system-msg state side "takes 1 brain damage from Stim Dealer"))
                             (do (add-counter state side card :power 1)
                                 (gain state side :click 1)
                                 (system-msg state side "uses Stim Dealer to gain [Click]"))))}}}

   "Street Peddler"
   {:interactive (req (some #(card-flag? % :challenger-place-draw true) (all-active state :challenger)))
    :effect (req (doseq [c (take 3 (:deck challenger))]
                   (host state side (get-card state card) c {:facedown true})))
    :abilities [{:req (req (not (place-locked? state side)))
                 :prompt "Choose a card on Street Peddler to place"
                 :choices (req (cancellable (filter #(and (not (is-type? % "Event"))
                                                          (challenger-can-place? state side % nil)
                                                          (can-pay? state side nil (modified-place-cost state side % [:credit -1])))
                                                    (:hosted card))))
                 :msg (msg "place " (:title target) " lowering its place cost by 1 [Credits]")
                 :effect (req
                           (when (can-pay? state side nil (modified-place-cost state side target [:credit -1]))
                             (place-cost-bonus state side [:credit -1])
                             (discard state side (update-in card [:hosted]
                                                          (fn [coll]
                                                            (remove-once #(= (:cid %) (:cid target)) coll)))
                                    {:cause :ability-cost})
                             (challenger-place state side (dissoc target :facedown))))}]}

   "Symmetrical Visage"
   {:events {:challenger-click-draw {:req (req (genetics-trigger? state side :challenger-click-draw))
                                 :msg "gain 1 [Credits]"
                                 :effect (effect (gain-credits 1))}}}

   "Synthetic Blood"
   {:events {:damage {:req (req (genetics-trigger? state side :damage))
                      :msg "draw 1 card"
                      :effect (effect (draw :challenger))}}}

   "Tallie Perrault"
   {:abilities [{:label "Draw 1 card for each Contestant bad publicity"
                 :effect (effect (discard card {:cause :ability-cost})
                                 (draw (+ (:bad-publicity contestant) (:has-bad-pub contestant))))
                 :msg (msg "draw " (:bad-publicity contestant) " cards")}]
    :events {:play-operation
             {:req (req (or (has-subtype? target "Black Ops")
                            (has-subtype? target "Gray Ops")))
              :effect (req (show-wait-prompt state :contestant "Challenger to use Tallie Perrault")
                           (resolve-ability
                             state :challenger
                             {:optional
                              {:prompt "Use Tallie Perrault to give the Contestant 1 bad publicity and take 1 tag?"
                               :player :challenger
                               :yes-ability {:msg "give the Contestant 1 bad publicity and take 1 tag"
                                             :async true
                                             :effect (effect (gain-bad-publicity :contestant 1)
                                                             (gain-tags :challenger eid 1)
                                                             (clear-wait-prompt :contestant))}
                               :no-ability {:effect (effect (clear-wait-prompt :contestant))}}}
                            card nil))}}}

   "Tech Trader"
   {:events {:challenger-discard {:req (req (and (= side :challenger) (= (second targets) :ability-cost)))
                            :msg "gain 1 [Credits]"
                            :effect (effect (gain-credits 1))}}}

   "Technical Writer"
   {:events {:challenger-place {:silent (req true)
                              :req (req (some #(= % (:type target)) '("Hazard" "Resource")))
                              :effect (effect (add-counter :challenger card :credit 1)
                                              (system-msg (str "places 1 [Credits] on Technical Writer")))}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (get-counters card :credit) " [Credits]")
                 :effect (effect (discard card {:cause :ability-cost})
                                 (gain-credits (get-counters card :credit)))}]}

   "Temple of the Liberated Mind"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:label "Gain [Click]"
                 :counter-cost [:power 1]
                 :req (req (= (:active-player @state) :challenger))
                 :msg "gain [Click]" :once :per-turn
                 :effect (effect (gain :click 1))}]}

   "Temüjin Contract"
   {:data {:counter {:credit 20}}
    :prompt "Choose a locale for Temüjin Contract"
    :choices (req locales)
    :msg (msg "target " target)
    :req (req (not (:locale-target card)))
    :effect (effect (update! (assoc card :locale-target target)))
    :events {:successful-run
             {:req (req (= (zone->name (get-in @state [:run :locale])) (:locale-target (get-card state card))))
              :msg "gain 4 [Credits]"
              :effect (req (let [creds (get-counters card :credit)]
                             (gain-credits state side 4)
                             (set-prop state side card :counter {:credit (- creds 4)})
                             (when (zero? (get-counters (get-card state card) :credit))
                               (discard state side card {:unpreventable true}))))}}}

   "The Archivist"
   {:in-play [:link 1]
    :events {:agenda-scored {:req (req (or (has-subtype? target "Initiative")
                                           (has-subtype? target "Security")))
                             :interactive (req true)
                             :async true
                             :msg "force the Contestant to initiate a trace"
                             :label "Trace 1 - If unsuccessful, take 1 bad publicity"
                             :trace {:base 1
                                     :unsuccessful
                                     {:effect (effect (gain-bad-publicity :contestant 1)
                                                      (system-msg :contestant (str "takes 1 bad publicity")))}}}}}

   "The Black File"
   {:msg "prevent the Contestant from winning the game unless they are flatlined"
    :effect (req (swap! state assoc-in [:contestant :cannot-win-on-points] true))
    :events {:challenger-turn-begins
             {:effect (req (if (>= (get-counters card :power) 2)
                             (do (move state side (dissoc card :counter) :rfg)
                                 (swap! state update-in [:contestant] dissoc :cannot-win-on-points)
                                 (system-msg state side "removes The Black File from the game")
                                 (gain-agenda-point state :contestant 0))
                             (add-counter state side card :power 1)))}}
    :discard-effect {:effect (req (swap! state update-in [:contestant] dissoc :cannot-win-on-points)
                                (gain-agenda-point state :contestant 0))}
    :leave-play (req (swap! state update-in [:contestant] dissoc :cannot-win-on-points)
                     (gain-agenda-point state :contestant 0))}

   "The Helpful AI"
   {:in-play [:link 1]
    :abilities [{:msg (msg "give +2 strength to " (:title target))
                 :choices {:req #(and (has-subtype? % "Icebreaker")
                                      (placed? %))}
                 :effect (effect (update! (assoc card :hai-target target))
                                 (discard (get-card state card) {:cause :ability-cost})
                                 (update-breaker-strength target))}]
    :events {:challenger-turn-ends nil :contestant-turn-ends nil :pre-breaker-strength nil}
    :discard-effect {:effect
                   (effect (register-events
                             (let [hai {:effect (effect (unregister-events card)
                                                        (update! (dissoc card :hai-target))
                                                        (update-breaker-strength (:hai-target card)))}]
                               {:challenger-turn-ends hai :contestant-turn-ends hai
                                :pre-breaker-strength {:req (req (= (:cid target)(:cid (:hai-target card))))
                                                       :effect (effect (breaker-strength-bonus 2))}}) card))}}

   "The Shadow Net"
   (letfn [(events [challenger] (filter #(and (is-type? % "Event") (not (has-subtype? % "Priority"))) (:discard challenger)))]
     {:abilities [{:cost [:click 1 :forfeit]
                   :req (req (pos? (count (events challenger))))
                   :label "Play an event from your Heap, ignoring all costs"
                   :prompt "Choose an event to play"
                   :msg (msg "play " (:title target) " from the Heap, ignoring all costs")
                   :choices (req (cancellable (events challenger) :sorted))
                   :effect (effect (play-instant nil target {:ignore-cost true}))}]})

   "The Supplier"
   (let [ability  {:label "Place a hosted card (start of turn)"
                   :prompt "Choose a card hosted on The Supplier to place"
                   :req (req (some #(can-pay? state side nil (modified-place-cost state side % [:credit -2]))
                                        (:hosted card)))
                   :choices {:req #(and (= "The Supplier" (:title (:host %)))
                                        (= "Challenger" (:side %)))}
                   :once :per-turn
                   :effect (req
                             (challenger-can-place? state side target nil)
                             (when (and (can-pay? state side nil (modified-place-cost state side target [:credit -2]))
                                           (not (and (:uniqueness target) (in-play? state target))))
                                  (place-cost-bonus state side [:credit -2])
                                  (challenger-place state side target)
                                  (system-msg state side (str "uses The Supplier to place " (:title target) " lowering its place cost by 2"))
                                  (update! state side (-> card
                                                          (assoc :supplier-placed (:cid target))
                                                          (update-in [:hosted]
                                                                     (fn [coll]
                                                                       (remove-once #(= (:cid %) (:cid target)) coll)))))))}]
   {:flags {:drip-economy true}  ; not technically drip economy, but has an interaction with Drug Dealer
    :abilities [{:label "Host a radicle or piece of hazard" :cost [:click 1]
                 :prompt "Select a card to host on The Supplier"
                 :choices {:req #(and (#{"Radicle" "Hazard"} (:type %))
                                      (in-hand? %))}
                 :effect (effect (host card target)) :msg (msg "host " (:title target) "")}
                ability]

    ; A card placed by The Supplier is ineligible to receive the turn-begins event for this turn.
    :suppress {:challenger-turn-begins {:req (req (= (:cid target) (:supplier-placed (get-card state card))))}}
    :events {:challenger-turn-begins ability
             :challenger-turn-ends {:req (req (:supplier-placed card))
                                :effect (effect (update! (dissoc card :supplier-placed)))}}})

   "The Source"
   {:effect (effect (update-all-advancement-costs))
    :leave-play (effect (update-all-advancement-costs))
    :events {:agenda-scored {:effect (effect (discard card))}
             :agenda-stolen {:effect (effect (discard card))}
             :pre-advancement-cost {:effect (effect (advancement-cost-bonus 1))}
             :pre-steal-cost {:effect (effect (steal-cost-bonus [:credit 3]))}}}

   "The Turning Wheel"
   {:events {:agenda-stolen {:effect (effect (update! (assoc card :agenda-stolen true)))
                             :silent (req true)}
             :pre-access {:req (req (and (:run @state)
                                         (pos? (get-in @state [:run :ttw-bonus] 0))))
                          :effect (req (let [ttw-bonus (get-in @state [:run :ttw-bonus] 0)
                                             deferred-bonus (get-in @state [:run :ttw-deferred-bonus] 0)]
                                         (if (#{:hq :rd} target)
                                           (when (pos? deferred-bonus)
                                             (access-bonus state side ttw-bonus)
                                             (swap! state assoc-in [:run :ttw-deferred-bonus] 0))
                                           (when (zero? deferred-bonus)
                                             (access-bonus state side (- ttw-bonus))
                                             (swap! state assoc-in [:run :ttw-deferred-bonus] ttw-bonus)))))
                          :silent (req true)}
             :run-ends {:effect (req (when (and (not (:agenda-stolen card))
                                                (#{:hq :rd} target))
                                       (add-counter state side card :power 1)
                                       (system-msg state :challenger (str "places a power counter on " (:title card))))
                                     (update! state side (dissoc (get-card state card) :agenda-stolen)))
                        :silent (req true)}}
    :abilities [{:counter-cost [:power 2]
                 :req (req (:run @state))
                 :msg "access 1 additional card from HQ or R&D for the remainder of the run"
                 :effect  (req (swap! state update-in [:run :ttw-bonus] (fnil inc 0))
                               (access-bonus state side 1))}]}

   "Theophilius Bagbiter"
   {:effect (req (lose-credits state :challenger :all)
                 (lose state :challenger :run-credit :all)
                 (swap! state assoc-in [:challenger :hand-size :base] 0)
                 (add-watch state :theophilius-bagbiter
                            (fn [k ref old new]
                              (let [credit (get-in new [:challenger :credit])]
                                (when (not= (get-in old [:challenger :credit]) credit)
                                  (swap! ref assoc-in [:challenger :hand-size :base] credit))))))
    :leave-play (req (remove-watch state :theophilius-bagbiter)
                     (swap! state assoc-in [:challenger :hand-size :base] 5))}

   "Thunder Art Gallery"
   (let [first-event-check (fn [state fn1 fn2] (and (fn1 state :challenger :challenger-lose-tag #(= :challenger (second %)))
                                            (fn2 state :challenger :challenger-prevent (fn [t] (seq (filter #(some #{:tag} %) t))))))
         ability {:choices {:req #(and (= "Challenger" (:side %))
                                       (in-hand? %)
                                       (not (is-type? % "Event")))}
                  :async true
                  :prompt (msg "Select a card to place with Thunder Art Gallery")
                  :effect (req (if (and (challenger-can-place? state side target)
                                        (can-pay? state side target
                                                  (place-cost state side target [:credit (dec (:cost target))])))
                                 (do (place-cost-bonus state side [:credit -1])
                                     (system-msg state side "uses Thunder Art Gallery to place a card.")
                                     (challenger-place state side eid target nil))
                                 (effect-completed state side eid)))
                  :cancel-effect (effect (effect-completed eid))}]
     {:events {:challenger-lose-tag (assoc ability :req (req (and (first-event-check state first-event? no-event?) (= side :challenger))))
               :challenger-prevent (assoc ability :req (req (and (first-event-check state no-event? first-event?) (seq (filter #(some #{:tag} %) targets)))))}})

   "Tri-maf Contact"
   {:abilities [{:cost [:click 1] :msg "gain 2 [Credits]" :once :per-turn
                 :effect (effect (gain-credits 2))}]
    :discard-effect {:effect (effect (damage eid :meat 3 {:unboostable true :card card}))}}

   "Tyson Observatory"
   {:abilities [{:prompt "Choose a piece of Hazard" :msg (msg "add " (:title target) " to their Grip")
                 :choices (req (cancellable (filter #(is-type? % "Hazard") (:deck challenger)) :sorted))
                 :cost [:click 2]
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (move target :hand))}]}

   "Underworld Contact"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :once :per-turn
                  :effect (req (when (and (>= (:link challenger) 2)
                                          (:challenger-phase-12 @state))
                                 (system-msg state :challenger (str "uses " (:title card) " to gain 1 [Credits]"))
                                 (gain-credits state :challenger 1)))}]
   {:flags {:drip-economy true}
    :abilities [ability]
    :events {:challenger-turn-begins ability}})

   "Utopia Shard"
   (shard-constructor :hq "force the Contestant to discard 2 cards from HQ at random"
                      (effect (discard-cards :contestant (take 2 (shuffle (:hand contestant))))))

   "Virus Breeding Ground"
   {:events {:challenger-turn-begins {:effect (effect (add-counter card :virus 1))}}
    :abilities [{:cost [:click 1]
                 :req (req (pos? (get-counters card :virus)))
                 :effect (req (resolve-ability
                                state side
                                {:msg (msg "move 1 virus counter to " (:title target))
                                 :choices {:req #(pos? (get-virus-counters state side %))}
                                 :effect (req (add-counter state side card :virus -1)
                                              (add-counter state side target :virus 1))}
                                card nil))}]}

   "Wasteland"
   {:events {:challenger-discard {:req (req (and (first-placed-discard-own? state :challenger)
                                           (placed? target)
                                           (= (:side target) "Challenger")))
                            :effect (effect (gain-credits 1))
                            :msg "gain 1 [Credits]"}}}

   "Wireless Net Pavilion"
   {:effect (effect (discard-radicle-bonus -2))
    :leave-play (effect (discard-radicle-bonus 2))}

   "Woman in the Red Dress"
   (let [ability {:msg (msg "reveal " (:title (first (:deck contestant))) " on the top of R&D")
                  :label "Reveal the top card of R&D (start of turn)"
                  :once :per-turn
                  :req (req (:challenger-phase-12 @state))
                  :effect (effect (show-wait-prompt :challenger "Contestant to decide whether or not to draw with Woman in the Red Dress")
                                  (resolve-ability
                                    {:optional
                                     {:player :contestant
                                      :prompt (msg "Draw " (:title (first (:deck contestant))) "?")
                                      :yes-ability {:effect (effect (clear-wait-prompt :challenger)
                                                                    (system-msg (str "draws " (:title (first (:deck contestant)))))
                                                                    (draw))}
                                      :no-ability {:effect (effect (clear-wait-prompt :challenger)
                                                                   (system-msg "doesn't draw with Woman in the Red Dress"))}}}
                                    card nil))}]
   {:events {:challenger-turn-begins ability}
    :abilities [ability]})

   "Wyldside"
   {:flags {:challenger-turn-draw true
            :challenger-phase-12 (req (< 1 (count (filter #(card-flag? % :challenger-turn-draw true)
                                                      (cons (get-in @state [:challenger :identity])
                                                            (all-active-placed state :challenger))))))}

    :events {:challenger-turn-begins {:effect (req (lose state side :click 1)
                                               (when-not (get-in @state [:per-turn (:cid card)])
                                                 (system-msg state side "uses Wyldside to draw 2 cards and lose [Click]")
                                                 (draw state side 2)))}}
    :abilities [{:msg "draw 2 cards and lose [Click]"
                 :once :per-turn
                 :effect (effect (draw 2))}]}

   "Xanadu"
   {:events {:pre-reveal-cost {:req (req (character? target))
                            :effect (effect (reveal-cost-bonus 1))}}}

   "Zona Sul Shipping"
   {:events {:challenger-turn-begins {:effect (effect (add-counter card :credit 1))}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (get-counters card :credit) " [Credits]")
                 :label "Take all credits"
                 :effect (effect (gain-credits (get-counters card :credit))
                                 (add-counter card :credit
                                              (- (get-counters card :credit))))}]
    :effect (req (add-watch state (keyword (str "zona-sul-shipping" (:cid card)))
                            (fn [k ref old new]
                              (when (is-tagged? new)
                                (remove-watch ref (keyword (str "zona-sul-shipping" (:cid card))))
                                (discard ref :challenger card)
                                (system-msg ref side "discards Zona Sul Shipping for being tagged")))))
    :reactivate {:effect (req (when tagged
                                (discard state :challenger card {:unpreventable true})))}}})