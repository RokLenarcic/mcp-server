(ns org.clojars.roklenarcic.mcp-server.resource.lookup
  "Implementation of Resources protocol with different use-cases."
  (:require [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.resources :as h.res]
            [org.clojars.roklenarcic.mcp-server.resources :as res]))

(defrecord LookupMapResources [support-subscriptions?]
  res/Resources
  (supports-list-changed? [this] true)
  (supports-subscriptions? [this] support-subscriptions?)
  (list-resources [this exchange cursor]
    (let [resources (::mcp/resource-list @(c/get-session exchange))]
      {:next-cursor nil :resources (mapv #(dissoc % :handler) (vals resources))}))
  (get-resource [this exchange uri]
    (get-in @(c/get-session exchange) [::mcp/resource-list uri]))
  (subscribe [this exchange uri]
    (swap! (c/get-session exchange) update ::mcp/resource-subscriptions (fnil conj #{}) uri))
  (unsubscribe [this exchange uri]
    (swap! (c/get-session exchange) update ::mcp/resource-subscriptions disj uri))
  (subscribed? [this exchange uri]
    (contains? (::mcp/resource-subscriptions @(c/get-session exchange)) uri)))

(defn lookup-map
  "The resources are maintained as a map in a session."
  [support-subscriptions?]
  (->LookupMapResources support-subscriptions?))

(defn add-resource
  "Adds resource to session's resource list. Assumes you're using lookup-map resources implementation.

  Handler is (fn [exchange uri] ...)"
  [session resource handler]
  (let [res (assoc resource :handler handler)
        uri (:uri res)]
    (swap! session update ::mcp/resource-list assoc uri res)
    (h.res/notify-changed-list session)
    session))

(defn remove-resource
  "Removes a resource from a resource list. Assumes you're using lookup-map resources implementation."
  [session uri]
  (swap! session update ::mcp/resource-list dissoc uri)
  (rpc/send-notification session "notifications/resources/list_changed" nil)
  session)