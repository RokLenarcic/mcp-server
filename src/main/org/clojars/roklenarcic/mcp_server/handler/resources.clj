(ns org.clojars.roklenarcic.mcp-server.handler.resources
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.resources :as res]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply camelcase-keys]])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn resources' [rpc-session] (-> @rpc-session ::mcp/handlers :resources))

(defn resources-list [rpc-session {:keys [cursor]}]
  (if-let [res (resources' rpc-session)]
    (res/list-resources res (common/create-req-session rpc-session) cursor)
    (c/invalid-params "Resources are not supported")))

(defn wrap-resource [handler]
  (fn [rpc-session {:keys [uri]}]
    (if-let [resources (resources' rpc-session)]
      (if (string? uri)
        (let [exchange (common/create-req-session rpc-session)]
          (if-let [res (res/get-resource resources exchange uri)]
            (handler exchange res)
            (c/resource-not-found uri)))
        (c/invalid-params "Param 'uri' needs to be a string."))
      (c/invalid-params "Resources are not supported"))))

(defn get-resource-result [resp req-uri]
  (if (instance? JSONRPCError resp)
    resp
    {:contents (mapv (partial common/proto->resource req-uri)
                     (if (satisfies? p/ResourceResponse resp) [resp] resp))}))

(defn resources-read [exchange res]
  (-> ((:handler res) exchange (:uri res))
      (papply get-resource-result (:uri res))))

(defn subscribe [exchange res]
  (res/subscribe (resources' (c/get-session exchange)) exchange (:uri res))
  (:uri res))

(defn unsubscribe [exchange res]
  (res/unsubscribe (resources' (c/get-session exchange)) exchange (:uri res))
  (:uri res))

(defn notify-changed [rpc-session uri]
  (let [resources (resources' rpc-session)
        exchange (common/create-req-session rpc-session)]
    (when (and (res/supports-subscriptions? resources)
               (::mcp/initialized? @rpc-session)
               (res/subscribed? resources exchange uri))
      (rpc/send-notification (c/get-session exchange) "notifications/resources/updated" {:uri uri}))))

(defn notify-changed-list [rpc-session]
  (when (::mcp/initialized? @rpc-session)
    (rpc/send-notification rpc-session "notifications/resources/list_changed" nil)))

(defn templates-list [rpc-session _]
  (log/debug "Templates list called")
  {:resourceTemplates (get-in @rpc-session [::mcp/handlers :resource-templates])})

(defn ->resource-template [all]
  (-> all
      (update :annotations (fn [annotations]
                             (when annotations
                               (mapv #(update % :audience common/to-role-list) annotations))))
      camelcase-keys))

(defn add-resources-handlers [m]
  (assoc m "resources/list" (wrap-check-init resources-list)
           "resources/read" (wrap-check-init (wrap-resource resources-read))
           "resources/subscribe" (wrap-check-init (wrap-resource subscribe))
           "resources/unsubscribe" (wrap-check-init (wrap-resource unsubscribe))
           "resources/templates/list" (wrap-check-init templates-list)))