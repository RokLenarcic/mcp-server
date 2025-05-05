# Lookup map Resources

A simple implementation that keeps a lookup map of resources.

It can be found in `org.clojars.roklenarcic.mcp-server.resource.lookup`.

```clojure
(server/set-resources-handler session (lookup/lookup-map true))
```
The sole parameter specifies whether support for subscriptions should be advertised to
clients.

Add or remove resources from primordial or live sessions:

```clojure
(lookup/add-resource session
                     (core/resource-desc "file:///a-resource.txt" 
                                         "My Resource" 
                                         "A big file of works" 
                                         "text/plain" 
                                         nil)
                     (fn [exchange uri]
                       ...))
(lookup/remove-resource session "file:///a-resource.txt")
```

Adding or removing the resources will notify client that the resource list has changed.

## Handler responses

```clojure
;; body should be String, byte[] or InputStream
;; if uri is nil, request URI is returned instead
(core/resource body mime-type uri)

;; provide your own
(reify org.clojars.roklenarcic.mcp-server.protocol/ResourceResponse
  (-res-body [this])
  (-res-mime [this])
  (-res-uri [this]))
```

## Subscriptions

If you have elected to declare support for subscriptions, you will need to notify the client (via a session) when
a specific resource changes.

```clojure
;; you don't need to check if the client is subscribed, that is done for you
(server/notify-resource-changed session "file:///a-resource.txt")
```