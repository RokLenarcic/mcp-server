(ns org.clojars.roklenarcic.mcp-server.handler.init
  (:require [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.util :refer [?assoc]]
            [org.clojars.roklenarcic.mcp-server.resources :as resources]))

(def server-protocol-version "2025-03-26")

(def allowed-protocol-versions #{"2025-03-26" "2024-11-05" "2025-06-18"})

(defn ->capabilities [server-info handlers]
  (cond-> {}
    (:logging server-info) (assoc :logging {})
    (or (:def-completion handlers)
        (:completions handlers)) (assoc :completions {})
    (not-empty (:prompts handlers)) (assoc :prompts {:listChanged false})
    (not-empty (:tools handlers)) (assoc :tools {:listChanged true})
    (some? (:resources handlers)) (assoc :resources
                                         {:subscribe (resources/supports-subscriptions? (:resources handlers)),
                                          :listChanged (resources/supports-list-changed? (:resources handlers))})))

(defn initialize-handler [rpc-session {:keys [protocolVersion capabilities clientInfo]}]
  (let [{::mcp/keys [server-info handlers] :as sess} @rpc-session]
    (if (allowed-protocol-versions protocolVersion)
      (if (some? (::mcp/initialized? sess))
        (c/invalid-params "Session is initialized already")
        (do (swap! rpc-session
                   assoc
                   ::mcp/initialized? false
                   ::mcp/client-capabilities capabilities
                   ::mcp/client-info clientInfo
                   ::mcp/protocol-version protocolVersion)
            (?assoc {:protocolVersion protocolVersion
                     :serverInfo {:name (:name server-info) :version (:version server-info)}
                     :capabilities (->capabilities server-info handlers)}
                    :instructions (:instructions server-info))))
      (c/invalid-request (format "Invalid protocol version %s, supported version %s" protocolVersion allowed-protocol-versions)))))

(defn init-notify-handler [rpc-session _]
  (swap! rpc-session update ::mcp/initialized? #(if (false? %) true %))
  nil)

(defn add-init-handlers [m]
  (assoc m "initialize" initialize-handler "notifications/initialized" init-notify-handler))