(ns org.clojars.roklenarcic.mcp-server.resources
  "This namespace defines the Resources protocol that provides the interface
   for serving resource endpoints in an MCP server. Resources represent files,
   data, or content that can be accessed by clients through URIs.")

(defprotocol Resources
  "Protocol for providing resource endpoints to an MCP server.
   
   This protocol defines the interface for resource providers that can:
   - List available resources with optional pagination
   - Serve individual resource content
   - Support subscriptions for change notifications
   - Handle resource lifecycle management
   
   Implementations should handle thread safety and proper error reporting."
  
  (supports-list-changed? [this] 
    "Indicates whether this resource provider supports list change notifications.
     
     Returns true if the provider can notify clients when the list of available
     resources changes, false otherwise. When true, clients can rely on
     notifications instead of polling for resource list updates.
     
     Returns: boolean")
  
  (supports-subscriptions? [this] 
    "Indicates whether this resource provider supports individual resource subscriptions.
     
     Returns true if the provider can notify clients when individual resources
     change their content, false otherwise. When true, clients can subscribe
     to specific resources and receive notifications when they are modified.
     
     Returns: boolean")
  
  (list-resources [this exchange cursor] 
    "Lists all available resources, with optional pagination support.
     
     Parameters:
     - this: the resource provider instance
     - exchange: RequestExchange object for accessing client context
     - cursor: optional pagination cursor (string or nil)
     
     Returns a map containing:
     - :next-cursor: string cursor for next page, or nil if no more pages
     - :resources: vector of resource description maps
     
     Each resource description should be created using core/resource-desc
     and contain: :uri, :name, :description, :mime-type, and optional :annotations")
  
  (get-resource [this exchange uri] 
    "Retrieves a specific resource by URI for processing client requests.
     
     Parameters:
     - this: the resource provider instance
     - exchange: RequestExchange object for accessing client context
     - uri: string URI of the requested resource
     
     Returns a resource description map with an additional :handler key,
     or nil if the resource doesn't exist. The :handler value should be
     a function (fn [exchange uri] -> core/resource) that generates the
     actual resource content when called.
     
     The returned map should contain the same fields as resource descriptions
     plus the :handler function for content generation.")
  
  (subscribe [this exchange uri] 
    "Subscribes the client to receive notifications when a resource changes.
     
     Parameters:
     - this: the resource provider instance  
     - exchange: RequestExchange object for accessing client context
     - uri: string URI of the resource to subscribe to
     
     This method should track the subscription internally and ensure that
     the client receives notifications when the specified resource changes.

     Returns this")
  
  (unsubscribe [this exchange uri] 
    "Removes a client's subscription to a resource.
     
     Parameters:
     - this: the resource provider instance
     - exchange: RequestExchange object for accessing client context  
     - uri: string URI of the resource to unsubscribe from
     
     This method should remove the subscription tracking and stop sending
     notifications for the specified resource to this client.

     Returns this")
  
  (subscribed? [this exchange uri] 
    "Checks whether the client is currently subscribed to a resource.
     
     Parameters:
     - this: the resource provider instance
     - exchange: RequestExchange object for accessing client context
     - uri: string URI to check subscription status for
     
     Returns true if the client is subscribed to notifications for this
     resource, false otherwise."))