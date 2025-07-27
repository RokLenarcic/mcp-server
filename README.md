# MCP Server

A lightweight Clojure library for building MCP (Model Context Protocol) servers. This library prioritizes flexibility and minimal dependencies, allowing you to integrate with your existing technology stack.

The library is currently in alpha stage with features being added incrementally.

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/mcp-server.svg)](https://clojars.org/org.clojars.roklenarcic/mcp-server)

## Why Use This Library?

Existing MCP server implementations often force specific technology choices on you - they bundle JSON parsers, web servers, loggers, and synchronous/asynchronous patterns. This library takes a different approach by letting you choose your own components.

<details>
<summary>Why I Built This Instead of Using Existing Solutions</summary>

I initially tried wrapping the official Java MCP server from https://github.com/modelcontextprotocol/java-sdk, but encountered several issues:

- **Heavy dependencies**: Jackson, Reactor, and other large libraries
- **Java version requirements**: Uses Java records (Java 14 Preview/Java 16 standard)
- **Integration problems**: Couldn't get Jackson working properly with records
- **Forced logging**: Uses SLF4J with no alternative options
- **Complex async patterns**: Uses Reactive streams, which are difficult to debug
- **Always async**: Even "sync" servers use async internally
- **Framework assumptions**: Designed for Spring integration, not useful in Clojure

The complexity didn't match the value. If you want to build a simple STDIO MCP server with basic tools, why should you need to bundle a web server and deal with reactive flows?

</details>

## Current Alpha Limitations

These features are not yet implemented:

- Pagination support
- Tool parameter schema validation
- Protocol version 2025-06-18 support

The API may change before the stable release.

## Quick Start

First, choose a JSON serializer and add it to your dependencies. This example uses Charred:

```clojure
;; Add to deps.edn
com.cnuernber/charred {:mvn/version "1.037"}
```

Here's a complete weather service example:

```clojure
(ns example.weather
  (:require [org.clojars.roklenarcic.mcp-server.json.charred :as json]
            [org.clojars.roklenarcic.mcp-server.server :as server]))

(def server-info
  (server/server-info "Weather Service"
                      "1.0.0"
                      "This service provides various weather data"))

(defn get-weather [exchange {:keys [location]}]
  (format "Weather at location %s is %s, temperature is %s degrees"
          location
          (rand-nth ["sunny" "cloudy" "snowing" "rainy" "hailstorm"])
          (- 20 (rand-int 60))))

(def tool (server/tool
            "get_current_weather"
            "Reports current weather based on the location"
            (server/obj-schema nil
                               {:location (server/str-schema "Location of interest" nil)}
                               ["location"])
            get-weather))

(defn start []
  ;; Create template session
  (let [session (-> (server/make-session
                      ;; plug in some general server config
                      server-info
                      ;; pick a JSON serialization implementation
                      (json/serde {})
                      ;; additional context in the sessions
                      {})
                    ;; add a tool to that
                    (server/add-tool tool))]
    ;; Start STDIO server
    (server/start-server-on-streams session System/in System/out {})))
```

This creates a mock weather service that communicates over STDIO. The key components are:

- **JSON serialization**: We chose Charred for JSON handling
- **Session creation**: Created a session and added our tool
- **Transport**: Started a stream-based server on stdin/stdout

## Alternative: Manual Configuration

Since sessions are just maps, you can build them manually instead of using the helper functions:

```clojure
(ns example.weather2
  (:require [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.json.charred :as json]
            [org.clojars.roklenarcic.mcp-server.server :as server]))

(defn get-weather [exchange {:keys [location]}]
  (format "Weather at location %s is %s, temperature is %s degrees"
          location
          (rand-nth ["sunny" "cloudy" "snowing" "rainy" "hailstorm"])
          (- 20 (rand-int 60))))

(defn start []
  (let [session-map {::mcp/server-info {:name "Weather Service"
                                        :version "1.0.0"
                                        :instructions "This service provides various weather data"}
                     ::mcp/serde (json/serde {})
                     ::mcp/dispatch-table (server/make-dispatch)
                     ::mcp/handlers
                     {:tools
                      {"get_current_weather" 
                       {:name "get_current_weather"
                        :description "Reports current weather based on the location"
                        :input-schema {:properties {:location {:type "string" 
                                                              :description "Location of interest"}}
                                       :required ["location"]
                                       :type "object"}
                        :handler get-weather}}}}]
    (server/start-server-on-streams (atom session-map) System/in System/out {})))
```

The dispatch table is a lookup map that routes JSON-RPC calls to their handlers.

## JSON Serializers

You can write your own integration, by extending the `org.clojars.roklenarcic.mcp-server.json-rpc/JSONSerialization` protocol, but there are many available already:

- org.clojars.roklenarcic.mcp-server.json.babashka/serde
- org.clojars.roklenarcic.mcp-server.json.charred/serde
- org.clojars.roklenarcic.mcp-server.json.cheshire/serde
- org.clojars.roklenarcic.mcp-server.json.clj-data/serde
- org.clojars.roklenarcic.mcp-server.json.jsonista/serde

You need to add your own dependency for selected JSON serialization. 

## Key Namespaces

```clojure
[org.clojars.roklenarcic.mcp-server.server :as server]
[org.clojars.roklenarcic.mcp-server.core :as core]
[org.clojars.roklenarcic.mcp-server :as-alias mcp]
```

## Session Management

The session is the central abstraction - it's a map stored in an atom that represents a client connection. Sessions allow you to:

- Store connection-specific data (database pools, authentication info)
- Modify internal behavior (change RPC handlers)
- Manage available tools, prompts, and resources

See the [Session Guide](doc/session.md) for detailed information (**essential reading**).

## Execution Models

This library supports both synchronous and asynchronous execution patterns. **Your handlers can return plain values or CompletableFuture instances.**

By default, handlers run synchronously. Functions that send requests to clients return `CompletableFuture` objects (or `nil` if the operation isn't supported).

See the [Sync/Async Guide](doc/async.md) for different approaches.

## Handler Functions

Most handlers receive an `exchange` parameter first. This is a `RequestExchange` object that contains the session and provides access to client communication functions.

Use `core/get-session` to extract the session from an exchange. Functions like `log-msg`, `list-roots`, and `sampling` send requests to the client and return CompletableFuture objects.

Outside of handlers, create an exchange from a session using `server/exchange`.

## Error Handling

Your handlers can return RPC error objects that will be sent to clients. Use the functions in the `core` namespace:

```clojure
;; For invalid input parameters
(core/invalid-params "Size should be one of S, M, L, XL")

;; For server-side problems
(core/internal-error "Database connection failed")

;; For missing resources
(core/resource-not-found "Cannot find URL")

;; For malformed requests (rarely used)
(core/invalid-request "Request format not understood")

;; For custom application errors
(core/->JSONRPCError -32123 "Application specific error" "Additional details")
```

## Tools

Tools can be added or removed from both template sessions and live sessions. When you modify a live session's tool list, the client automatically receives a notification.

```clojure
(defn get-weather [exchange arguments] ...)

(def tool (server/tool
            "get_current_weather"
            "Reports current weather based on the location"
            (server/obj-schema nil
                               {:location (server/str-schema "Location of interest" nil)}
                               ["location"])
            get-weather))

;; Add or remove tools (returns the session atom)
(server/add-tool session tool)
(server/remove-tool session "get_current_weather")
;; Client is automatically notified of changes
```

### Tool Schemas

Parameter schemas use standard JSON Schema format. The `server` namespace provides helper functions for common patterns:

```clojure
(server/obj-schema nil 
                   {:location (server/obj-schema 
                                "Location as coordinates"
                                {:longitude (server/num-schema "Longitude" -180.0 180.0 nil nil nil)
                                 :latitude (server/num-schema "Latitude" -180.0 180.0 nil nil nil)}
                                ["longitude" "latitude"])}
                   ["location"])
```

<details>
<summary>Using Raw Schema Maps</summary>

You can provide schemas as plain maps instead of using helper functions:

```clojure
{:description "Location as coordinates",
 :properties {:longitude {:description "Longitude", :minimum -180.0, :maximum 180.0, :type "number"},
              :latitude {:description "Latitude", :minimum -180.0, :maximum 180.0, :type "number"}},
 :required ["longitude" "latitude"],
 :type "object"}
```

Keywords are automatically converted to camelCase (e.g., `:exclusive-minimum` becomes `:exclusiveMinimum`).
</details>

### Tool Return Values

Tools return one or more Content objects. You can return a single object or a collection:

```clojure
;; Simple text response
"ABC"
["ABC"]

;; Text with priority and audience metadata
(core/text-content "ABC" 1.5 :user)

;; Other content types
(core/image-content (byte-array [1]) "image/jpeg" 1.5 [:user :assistant])
(core/audio-content (byte-array [1]) "audio/mpeg")

;; Embedded resources (multiple equivalent forms)
(byte-array [1])
(ByteArrayInputStream. (byte-array [1]))
(core/embedded-content (byte-array [1]))
(core/resource (byte-array [1]) "application/octet-stream" nil)

;; Text resources
(core/embedded-content "Text as resource")
(core/resource "Text as resource" "text/plain" nil)

;; Resource with URI
(core/embedded-content (core/resource "{\"a\": 1}" "text/json" "https://localhost/x.json"))

;; Mixed content
["ABC" (byte-array [1])]
```

Tools can also return specific error objects:

```clojure
(core/tool-error "Something went wrong")
```

## Prompts

Prompts work similarly to tools - they can be added or removed from sessions with automatic client notification:

```clojure
(defn code-review [exchange arguments]
  ...)

(def prompt (server/prompt "code_review" "Request Code Review"
                           {:code "Code to review"}
                           {}
                           code-review))

(server/add-prompt session prompt)
(server/remove-prompt session "code_review")
```

### Prompt Return Values

Prompt handlers return a description and one or more messages:

```clojure
;; Full response object
(core/prompt-resp "Our special review prompt" 
                  [(core/message nil (core/text-content "Here's the prompt"))])

;; Simplified forms (all equivalent)
(core/prompt-resp "Our special review prompt" [(core/text-content "Here's the prompt")])
;; for things like text you can skip wrapping in into context, and you can also skip vector if you have only 1 message
(core/prompt-resp "Our special review prompt" "Here's the prompt")
(core/prompt-resp "Our special review prompt" ["Here's the prompt"])
;; these all produce same thing

```

## Resources

Resource support is added by setting a resource handler that implements the `Resources` protocol:

```clojure
(server/set-resources-handler session resources)
```

Currently, one implementation is provided:
- [Lookup Map Resources](doc/resource-lookup.md)

## Resource Templates

Resource templates can be added or removed from sessions:

```clojure
(server/add-resource-template session 
                              "file:///{temp}.txt" 
                              "general_file" 
                              "General file template" 
                              "text/plain"
                              [{:audience [:user :assistant]
                                :priority 3.4}])

(server/remove-resource-template session "general_file")
```

## Completions

Completions provide autocomplete functionality:

```clojure
(defn completion [exchange name value]
  (core/completion-resp ["completion 1" "completion 2"]))

;; Add completion for specific prompts or resources
(server/add-completion server "ref/prompt" "test-prompt" completion)
(server/remove-completion server "ref/prompt" "test-prompt")
```

You can also set a general completion handler for unmatched requests:

```clojure
(server/set-completion-handler
  session
  (fn [exchange ref-type ref-name name value] 
    (core/completion-resp ["completion 1" "completion 2"])))
```

## Client Communication

### Logging

Send log messages to the client:

```clojure
(core/log-msg exchange :info "tool.weather" "Fetching weather data from weather.com" 
              {:credits-left 20000})
```

### Progress Reporting

Report progress updates during long-running operations:

```clojure
;; Simple progress message
(core/report-progress exchange {:message "Processing data..."})

;; Progress with completion percentage
(core/report-progress exchange {:progress 50 :total 100 :message "Halfway done..."})

;; Full progress information
(core/report-progress exchange {:progress 75 
                                :total 100 
                                :message "Almost finished processing..."})
```

Progress reporting returns `true` if the progress was sent to the client, or `false` if there's no progress token available (which means the client didn't request progress updates).

### Listing Roots

Roots can be listed via `exchange`, returning a CompletableFuture. If client declares the ability to notify on root list
changes, then the roots are cached, with cached being cleared based on client's notification.

```clojure
;; Basic root listing
(.thenApply (core/list-roots exchange) 
            (fn [roots]
              (mapv (fn [{:keys [name uri]}]
                      (println "Client root" name "at" uri))
                    roots)))

;; With progress callback
(.thenApply (core/list-roots exchange 
                             (fn [progress]
                               (println "Root listing progress:" (:message progress))))
            (fn [roots]
              (println "Got" (count roots) "roots")))
```

### Roots Change Notifications

Register callbacks for when client roots change:

```clojure
(server/set-roots-changed-callback session (fn [exchange] ...))
```

### Sampling

Request text generation from the client:

```clojure
;; Simple sampling request
(core/sampling-request "Simple sampling"
                       (core/model-preferences [{:name "claude-3"}] nil nil) 
                       nil 
                       nil)

;; Complex sampling with embedded resources
(core/sampling-request [(core/message :user 
                                      (core/embedded-content
                                        (core/resource "Complex sampling param"
                                                       "text/plain"
                                                       "file://some-file.txt")
                                        4.5
                                        :assistant))]
                       (core/model-preferences [{:name "claude-3"}] nil nil)
                       "System prompt"
                       15555) ; max tokens

;; Basic execution (returns nil if client doesn't support it)
(some-> (core/sampling exchange sampling-req)
        (.thenApply (fn [sampling-result]
                      ;; Result format:
                      ;; {:role "assistant",
                      ;;  :content {:type "text", :text "Response text"},
                      ;;  :model "claude-3-sonnet-20240307",
                      ;;  :stopReason "endTurn"}
                      ...)))

;; With progress callback to monitor generation progress
(some-> (core/sampling exchange 
                       sampling-req
                       (fn [progress]
                         (println "Sampling progress:" (:message progress)
                                  (:progress progress) "/" (:total progress))))
        (.thenApply (fn [sampling-result]
                      (println "Generation complete:" (:content sampling-result)))))
```

### Cancelling Server Requests to Client

When your server makes requests to the client (`list-roots`, `sampling`), you can cancel them using the `CompletableFuture` cancel method. When cancelled with `mayInterruptIfRunning=true`, the client is automatically notified:

```clojure
;; Start a request to the client
(let [future (core/sampling exchange sampling-req)]
  ;; Cancel it if needed (notifies client)
  (.cancel future true)  ; true = mayInterruptIfRunning, sends cancellation notification to client
  
  .....
  )
```

If future is derefed it will throw an Exception (as per usual Future contract).

### Handling Client Cancellation Requests

When the client cancels a request to your server (like a tool call), your handlers can detect this and respond appropriately:

```clojure
(defn long-running-tool [exchange arguments]
  ;; cancel-future completes if client cancels a request, 
  ;; with 'reason' String as value
  (let [cancel-future (core/req-cancelled-future exchange)]
    ;; has request been cancelled?
    (.isDone cancel-future)
    ;; non-blockingly return cancellation reason or nil if not-cancelled
    (.getNow cancel-future nil)
    ;; you can await cancellation
    (.get cancel-future)
    ;; or add an action
    (.thenApply cancel-future (fn [reason] (println "Client cancelled:" reason)))))
```
The return of a cancelled request handler are always ignored and won't be sent to the client.

## Logging

This project uses `clojure.tools.logging` for internal logging.

## Middleware

Dispatch table of JSON-RPC handlers can be modified using a middleware pattern, similar to Ring middleware.

```clojure
(rpc/with-middleware dispatch-table [[middleware1]
                                     [middleware2 :param1 :param2]])
```

The handlers in the dispatch table will be changed to:

```clojure
(-> handler
    (middleware2 :param1 :param2)
    (middleware1))
```

Note that this is how Reitit works, `middleware1` is outer-most.

Here's an example middleware:

```
(defn wrap-check-credentials
  "Checks credentials"
  [handler]
  (fn check-credentials [rpc-session params]
    (if (:user-id @rpc-session)
      (handler rpc-session params)
      (c/invalid-params "Wrong access."))))
```

Middleware can be supplied to the dispatch table creation function:

```clojure
(server/make-dispatch middleware)
```

## Errors

Any uncaught errors will be emitted as JSON-RPC internal errors, with Exception messages sent to the client. 

Easiest way to modify this behavior and substitute a different strategy is to use the middleware approach and
wrap the dispatch table handlers with middleware that performs your error handling strategy.

```clojure
(server/make-dispatch [[wrap-error-strategy]])
```

## Authentication

MCP specification mandates use of OAuth2 authentication when used with HTTP transport. The server receives a token
and uses the token to validate access. This process is very specific to each application so this library
provides no tools for working with these tokens.

The easiest way you can integrate your Authentication solution with this library is via the request meta.

This library provides a Ring handler. You can wrap handler with middleware to block all unauthorized requests to it.

Within your handlers the request meta that `RequestExchange` object provides is the request map itself, and 
you can add your own Authentication logic to your handlers. 

Copyright (c) 2025 Rok Lenarčič