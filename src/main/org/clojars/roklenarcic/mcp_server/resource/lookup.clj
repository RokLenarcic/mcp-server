(ns org.clojars.roklenarcic.mcp-server.resource.lookup
  "Implementation of Resources protocol with different use-cases.
   
   This implementation stores resources in a map within the session, making it
   suitable for scenarios where resources can be dynamically added and removed
   at runtime. It supports both subscription-based and polling-based resource
   access patterns."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.resources :as h.res]
            [org.clojars.roklenarcic.mcp-server.resources :as res]
            [org.clojars.roklenarcic.mcp-server.util :as util]))

(defrecord LookupMapResources [support-subscriptions?]
  res/Resources
  (supports-list-changed? [this] true)
  (supports-subscriptions? [this] support-subscriptions?)
  (list-resources [this exchange cursor]
    (let [resources (::mcp/resource-list @(c/get-session exchange))]
      {:next-cursor nil :resources (mapv #(util/camelcase-keys (dissoc % :handler)) (vals resources))}))
  (get-resource [this exchange uri]
    (get-in @(c/get-session exchange) [::mcp/resource-list uri]))
  (subscribe [this exchange uri]
    (swap! (c/get-session exchange) update ::mcp/resource-subscriptions (fnil conj #{}) uri))
  (unsubscribe [this exchange uri]
    (swap! (c/get-session exchange) update ::mcp/resource-subscriptions disj uri))
  (subscribed? [this exchange uri]
    (contains? (::mcp/resource-subscriptions @(c/get-session exchange)) uri)))

(defn lookup-map
  "Creates a lookup map resources implementation.
   
   This implementation stores resources in the session's ::mcp/resource-list map
   and optionally supports subscriptions for change notifications.
   
   Parameters:
   - support-subscriptions?: boolean indicating whether to enable subscription support
   
   Returns a LookupMapResources instance.

   Usage:
   (lookup-map true)   ; Enable subscriptions
   (lookup-map false)  ; Disable subscriptions"
  [support-subscriptions?]
  (->LookupMapResources support-subscriptions?))

(defn add-resource
  "Adds a resource to the session's resource list and notifies clients.
   
   This function assumes you're using the lookup-map resources implementation.
   The resource will be stored in the session and clients will be notified
   of the resource list change.
   
   Parameters:
   - session: the session atom
   - resource: resource description map (should include :uri, :name, :description, etc.)
   - handler: resource handler function (fn [exchange uri] ...)
   
   Returns the updated session atom.
   
   The handler function will be called when clients request the resource content.
   It should return content that satisfies the ResourceResponse protocol.
   
   Example:
   (add-resource session
     {:uri \"file://example.txt\"
      :name \"Example File\"
      :description \"A sample text file\"
      :mime-type \"text/plain\"}
     (fn [exchange uri]
       \"Hello, World!\"))"
  [session resource handler]
  (let [uri (:uri resource)]
    (log/info "Adding resource to session:" uri)
    (log/trace "Resource details:" (dissoc resource :handler))
    
    (when-not uri
      (throw (ex-info "Resource must have a :uri" {:resource resource})))
    
    (let [res (assoc resource :handler handler)]
      (swap! session update ::mcp/resource-list assoc uri res)
      (log/trace "Resource added to session map")
      
      ;; Notify clients that the resource list has changed
      (h.res/notify-changed-list session)
      (log/debug "Resource list change notification sent")
      
      session)))

(defn remove-resource
  "Removes a resource from the session's resource list and notifies clients.
   
   This function assumes you're using the lookup-map resources implementation.
   The resource will be removed from the session and clients will be notified
   of the resource list change.
   
   Parameters:
   - session: the session atom
   - uri: URI of the resource to remove
   
   Returns the updated session atom.
   
   Example:
   (remove-resource session \"file://example.txt\")"
  [session uri]
  (log/info "Removing resource from session:" uri)
  
  (let [existing-resource (get-in @session [::mcp/resource-list uri])]
    (if existing-resource
      (do
        (swap! session update ::mcp/resource-list dissoc uri)
        (log/trace "Resource removed from session map")
        
        ;; Notify clients that the resource list has changed
        (h.res/notify-changed-list session)
        (log/debug "Resource list change notification sent")
        
        session)
      (do
        (log/warn "Attempted to remove non-existent resource:" uri)
        session))))