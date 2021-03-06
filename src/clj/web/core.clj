(ns web.core
  (:require [web.api :refer [app]]
            [monger.collection :as mc]
            [cardnum.cards :as cards]
            [web.config :refer [frontend-version server-config server-mode]]
            [web.ws :as ws]
            [web.db :refer [db]]
            [web.chat :as chat]
            [web.lobby :as lobby]
            [web.game :as game]
            [web.stats :as stats]
            [cardnum.nav :as nav]
            [clj-time.format :as f]
            [game.core :as core])
  (:gen-class :main true))

(defonce server (atom nil))


(defn stop-server! []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn -main [& args]
  (let [port (or (-> server-config :web :port) 4141)]
    (web.db/connect)
    (let [cards (mc/find-maps db "cards" nil)
          sets (mc/find-maps db "sets" nil)
          mwl (mc/find-maps db "mwl" nil)]
      (core/reset-card-defs)
      (reset! cards/all-cards (into (into {} (map (juxt :title identity) cards))
              (map (juxt :code identity) cards)))
      (reset! cards/sets sets)
      (reset! cards/mwl mwl))

    (when (#{"dev" "prod"} (first args))
      (reset! server-mode (first args)))

    (if-let [config (mc/find-one-as-map db "config" nil)]
      (reset! frontend-version (:version config))
      (do (mc/create db "config" nil)
          (mc/insert db "config" {:version "0.1.0" :cards-version 0})))

    ;; Clear inactive lobbies after 15 minutes
    (web.utils/tick #(lobby/clear-inactive-lobbies 14400) 16000)
    (web.utils/tick lobby/send-lobby 1000)

    (reset! server (org.httpkit.server/run-server app {:port port}))
    (println "Cardnum server running in" @server-mode "mode on port" port)
    (println "Frontend version " @frontend-version))

  (ws/start-ws-router!))
