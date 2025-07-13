(ns org.clojars.roklenarcic.mcp-server.handler.resources
  "This namespace handles resource-related MCP protocol operations.
  Resources represent files, data, or content that can be accessed by clients.
  This handler manages resource listing, reading, subscriptions, and templates."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.resources :as res]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply camelcase-keys]])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn resources'
  "Retrieves the resources handler from a session.
   
   Parameters:
   - rpc-session: the session atom
   
   Returns the resources handler object or nil if not configured."
  [rpc-session] 
  (let [resources (-> @rpc-session ::mcp/handlers :resources)]
    (log/trace "Retrieved resources handler from session:" (some? resources))
    resources))

(defn resources-list
  "Handles resources/list requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - params: request parameters containing optional :cursor for pagination
   
   Returns a list of available resources or an error if resources not supported."
  [rpc-session {:keys [cursor]}]
  (log/debug "Client requested resource list" (when cursor (str " with cursor: " cursor)))
  
  (if-let [res (resources' rpc-session)]
    (do
      (log/trace "Delegating to resources handler for listing")
      (res/list-resources res (common/create-req-session rpc-session) cursor))
    (do
      (log/info "Resources list requested but resources not supported")
      (c/invalid-params "Resources are not supported"))))

(defn wrap-resource
  "Middleware that wraps resource access handlers with common validation.
   
   This middleware:
   1. Checks if resources are supported
   2. Validates the URI parameter
   3. Resolves the resource from the URI
   4. Calls the wrapped handler with the resolved resource
   
   Parameters:
   - handler: the handler function to wrap
   
   Returns a wrapped handler that performs resource validation and resolution."
  [handler]
  (fn [rpc-session {:keys [uri]}]
    (log/trace "Processing resource request for URI:" uri)
    
    (if-let [resources (resources' rpc-session)]
      (if (string? uri)
        (let [exchange (common/create-req-session rpc-session)]
          (log/trace "Attempting to resolve resource:" uri)
          (if-let [res (res/get-resource resources exchange uri)]
            (do
              (log/trace "Resource resolved successfully, calling handler")
              (handler exchange res))
            (do
              (log/warn "Resource not found:" uri)
              (c/resource-not-found uri))))
        (do
          (log/warn "Invalid URI parameter type:" (type uri))
          (c/invalid-params "Param 'uri' needs to be a string.")))
      (do
        (log/warn "Resource request but resources not supported")
        (c/invalid-params "Resources are not supported")))))

(defn get-resource-result
  "Converts a resource read result to MCP wire format.
   
   Parameters:
   - resp: resource read result (ResourceResponse, collection, or JSONRPCError)
   - req-uri: the URI that was requested
   
   Returns a map in MCP resource response format with :contents key."
  [resp req-uri]
  (if (instance? JSONRPCError resp)
    resp
    {:contents (mapv (partial common/proto->resource req-uri)
                     (if (satisfies? p/ResourceResponse resp) [resp] resp))}))

(defn resources-read
  "Handles resources/read requests from the client.
   
   Parameters:
   - exchange: request exchange object
   - res: resolved resource object containing :uri and :handler
   
   Returns the resource content or an error."
  [exchange res]
  (log/info "Reading resource:" (:uri res))
  (-> ((:handler res) exchange (:uri res))
      (papply get-resource-result (:uri res))))

(defn subscribe
  "Handles resources/subscribe requests from the client.

   Parameters:
   - exchange: request exchange object
   - res: resolved resource object containing :uri

   Returns the subscribed URI."
  [exchange res]
  (log/debug "Subscribing to resource:" (:uri res))

  (let [resources (resources' (c/get-session exchange))]
    (res/subscribe resources exchange (:uri res))
    (:uri res)))

(defn unsubscribe
  "Handles resources/unsubscribe requests from the client.

   Parameters:
   - exchange: request exchange object
   - res: resolved resource object containing :uri

   Returns the unsubscribed URI."
  [exchange res]
  (log/debug "Unsubscribing from resource:" (:uri res))
  (let [resources (resources' (c/get-session exchange))]
    (res/unsubscribe resources exchange (:uri res))
    (:uri res)))

(defn notify-changed
  "Notifies the client that a resource has changed, if subscriptions are enabled
   and the client has subscribed to this URI.

   Parameters:
   - rpc-session: the session atom
   - uri: URI of the resource that changed"
  [rpc-session uri]
  (let [resources (resources' rpc-session)
        exchange (common/create-req-session rpc-session)]
    (when (and (res/supports-subscriptions? resources)
               (::mcp/initialized? @rpc-session)
               (res/subscribed? resources exchange uri))
      (log/debug "Sending resource change notification for:" uri)
      (rpc/send-notification (c/get-session exchange) "notifications/resources/updated" {:uri uri}))))

(defn notify-changed-list [rpc-session]
  (when (::mcp/initialized? @rpc-session)
    (rpc/send-notification rpc-session "notifications/resources/list_changed" nil)))

(defn templates-list [rpc-session _]
  (log/trace "Templates list called")
  {:resourceTemplates (get-in @rpc-session [::mcp/handlers :resource-templates])})

(defn ->resource-template [all]
  (-> all
      (update :annotations (fn [annotations]
                             (when annotations
                               (log/debug "Processing" (count annotations) "template annotations")
                               (mapv #(update % :audience common/to-role-list) annotations))))
      camelcase-keys))

(defn add-resources-handlers
  "Adds resource-related handlers to the dispatch table.
   
   Parameters:
   - m: existing dispatch table map
   
   Returns the dispatch table with resource handlers added."
  [m]
  (assoc m
         "resources/list" (wrap-check-init resources-list)
         "resources/read" (wrap-check-init (wrap-resource resources-read))
         "resources/subscribe" (wrap-check-init (wrap-resource subscribe))
         "resources/unsubscribe" (wrap-check-init (wrap-resource unsubscribe))
         "resources/templates/list" (wrap-check-init templates-list)))
