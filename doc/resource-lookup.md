# Lookup Map Resources

This implementation provides a simple resource management system using an in-memory lookup map. It's suitable for servers with a known set of resources that don't change frequently.

## Setup

Import the lookup namespace and configure the resource handler:

```clojure
(require '[org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup])

(server/set-resources-handler session (lookup/lookup-map true))
```

The boolean parameter determines whether the server advertises subscription support to clients. Set it to `true` if you plan to notify clients about resource changes.

## Managing Resources

### Adding Resources

Add resources to either template sessions (before starting the server) or live sessions (during runtime):

```clojure
(lookup/add-resource session
                     (core/resource-desc "file:///a-resource.txt" 
                                         "My Resource" 
                                         "A big file of works" 
                                         "text/plain" 
                                         nil)
                     (fn [exchange uri]
                       ;; Resource handler function
                       "Resource content here"))
```

The resource handler function receives:
- `exchange`: The request context
- `uri`: The requested resource URI

### Removing Resources

Remove resources by their URI:

```clojure
(lookup/remove-resource session "file:///a-resource.txt")
```

### Automatic Notifications

When you add or remove resources from a live session, the client automatically receives a notification about the resource list change.

## Handler Response Types

Resource handlers can return different types of responses:

### Simple Responses

```clojure
;; String body with explicit MIME type
(core/resource "File content here" "text/plain" nil)

;; Binary data
(core/resource (byte-array [1 2 3 4]) "application/octet-stream" nil)

;; With custom URI (nil uses the request URI)
(core/resource "File content here" "text/plain" "file:///custom-path.txt")
```

### Custom Response Objects

For advanced use cases, implement the `ResourceResponse` protocol:

```clojure
(reify org.clojars.roklenarcic.mcp-server.protocol/ResourceResponse
  (-res-body [this] "Response body")
  (-res-mime [this] "text/plain")
  (-res-uri [this] "file:///custom-uri.txt"))
```

## Resource Subscriptions

If you enabled subscription support during setup, you are expected to trigger notifications to clients when specific resources change.

```clojure
(server/notify-resource-changed session "file:///a-resource.txt")
```

The system automatically checks if the client is subscribed to the resource before sending the notification. You don't need to track subscriptions manually.

## Example Implementation

Here's a complete example showing a file-based resource system:

```clojure
(require '[org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
         '[org.clojars.roklenarcic.mcp-server.core :as core])

(defn file-resource-handler [exchange uri]
  (try
    (let [content (slurp (str "/path/to/files/" (last (str/split uri #"/"))))]
      (core/resource content "text/plain" uri))
    (catch Exception e
      (core/resource-not-found (str "File not found: " uri)))))

(defn setup-file-resources [session]
  (let [files ["config.txt" "data.json" "readme.md"]]
    (doseq [filename files]
      (lookup/add-resource session
                           (core/resource-desc (str "file:///" filename)
                                               filename
                                               (str "Content of " filename)
                                               "text/plain"
                                               nil)
                           file-resource-handler))))

;; Initialize with subscription support
(server/set-resources-handler session (lookup/lookup-map true))
(setup-file-resources session)
```

This example creates a resource handler that serves files from a local directory and provides proper error handling for missing files.