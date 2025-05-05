(ns org.clojars.roklenarcic.mcp-server.resources)

(defprotocol Resources
  "A protocol that provides server with an ability to serve resource endpoints."
  (supports-list-changed? [this] "Returns true if this object supports notifications about changes
  to the list of resources")
  (supports-subscriptions? [this] "Returns true if this object supports subscribing to the individual
  resources changing")
  (list-resources [this exchange cursor] "Lists resources available, returns {:next-cursor :resources [...]}, where
  element of resources vector is core/resource-desc item.")
  (get-resource [this exchange uri] "Returns a resource, see core/resource-desc with :handler key, or nil,
  the :handler key is a (fn [exchange uri] core/resource)")
  (subscribe [this exchange uri] "Subscribes to a resource, can return invalid params error if this session
  cannot subscribe, or core/resource-not-found")
  (unsubscribe [this exchange uri] "Unsubscribe to a resource")
  (subscribed? [this exchange uri] "Returns true if session is subscribed to be notified for this uri"))