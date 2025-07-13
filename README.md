# MCP Server

This is a library for making MCP Servers. It prioritizes being
implementation agnostic and without dependencies.

The library is in alpha stage and features will be added
incrementally.

## Why?

There's already some existing software packages in this space. Most of them
are very opinionated, and they will force a JSON parser, a server, a logger and
a sync/async method on you, and you might have a pre-existing choices for those.

This library does none of that, at a price of requiring slightly more assembly for the advanced cases.

<details>
<summary>Here is why I decided to make my own</summary>

My first approach was to wrap the official Java MCP server at https://github.com/modelcontextprotocol/java-sdk

It was very complex wrapping job and when I tried to run that it had many problems:

- fat dependencies such as Jackson, and Reactor
- it uses java records which are Java 14 Preview Feature and Java 16 standard feature, not a big deal, but there's always someone who still uses older Java versions
- couldn't get Jackson to work with these records, there were issues getting it to run
- uses SLJ4J, you might want to use something else
- uses Reactive pattern for handling, it is a nightmare to debug, I hate it
- even if you start a Sync server it uses Async in the back so you're back to Reactive
- easy Spring integration is not a perk in Clojure space

It was just a big ball of complexity for no discernible gain. Some person might want to just
play with a simple Stdio MCP Server that has a trivial tool, why should they have to 
bundle a webserver and work with async flows?
</details>

## What does Alpha mean?

- pagination is not implemented yet
- API is not completely stable yet
- Authentication is not implemented
- Cancellation is not implemented
- Progress is not implemented
- Enforcing tool parameter schemas
- No support for protocol 2025-06-18

## Quick Start

You'll have to choose a JSON serializer and provide the corresponding dependency. In this example we'll be using Charred.

Add to deps.edn:

```clojure
com.cnuernber/charred {:mvn/version "1.037"}
```

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
  ;; create primordial session
  (let [session (-> (server/make-session
                      ;; plug in some general server config
                      server-info
                      ;; pick a JSON serialization implementation
                      (json/serde {})
                      ;; additional context in the sessions
                      {})
                    ;; add a tool to that
                    (server/add-tool tool))]
    ;; start a STDIO server
    (server/start-server-on-streams session System/in System/out {})))
```

This is a mock weather service that works over STDIO transport. The key ingredients are in `org.clojars.roklenarcic.mcp-server.server` namespace.

We have:
- picked a JSON serialization implementation (Charred)
- created a primordial session and added the tool to it
- started a streams based transport with that session on on `stdin` and `stdout` streams.

The session is just map, and the functions in `server` namespace assist with making the configuration maps, but they are not
strictly needed. This is an equivalent server:

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
  ;; create primordial session
  (let [session-map {::mcp/server-info {:name "Weather Service"
                                        :version "1.0.0"
                                        :instructions "This service provides various weather data"}
                     ::mcp/serde (json/serde {})
                     ::mcp/dispatch-table (server/make-dispatch)
                     ::mcp/handlers
                     {:tools
                      {"get_current_weather" {:name
                                              :description "Reports current weather based on the location"
                                              :input-schema {:properties {:location {:type "string", :description "Location of interest"}}
                                                             :required ["location"]
                                                             :type "object"}
                                              :handler get-weather}}}}]
    ;; start a STDIO server
    (server/start-server-on-streams (atom session-map) System/in System/out {})))
```

Dispatch is also just a lookup map of handlers for JSON RPC calls.

### Namespaces

```clojure
[org.clojars.roklenarcic.mcp-server.server :as server]
[org.clojars.roklenarcic.mcp-server.core :as core]
[org.clojars.roklenarcic.mcp-server :as-alias mcp]
```

## Session

The central abstraction is Session. The session is a map in an atom, and it roughly represents a connection to a client:

- you can add things to the session and that's available to handlers (e.g. DB connection pools, Auth data)
- you can modify/wrap internals (e.g. changing RPC handlers)
- you can modify tool/prompt/resource lists

See [Session cookbook](doc/session.md) for explanation (**very important read**).

## Execution model

This library tries to let you choose your own model. **Your handlers can return values or CompletableFuture instances.**

The default is that your handlers are called synchronously. 

Functions that send requests to the Client return `CompletableFuture` or `nil` (if operation is not possible, e.g. unsupported by the client).

See [Sync/async cookbook](doc/async.md) for approaches.

## Handlers

Handlers of various types usually receive `exchange` as the first parameter. That is `org.clojars.roklenarcic.mcp-server.core/RequestExchange`
object. With `core/get-session` you can extract session from an `exchange` while functions like `log-msg`, `list-roots` and `sampling` send
requests to the client, returning CompletableFuture or nil.

Outside of handlers you can create an `exchange` from a session with `server/exchange`.

### Errors

Any of your handlers can return RPC error objects, which will be relayed to clients. Use
function in the `core` namespace:

```clojure
;; preferred error for wrong inputs
(core/invalid-params "Size should be one of S, M, L, XL")

;; A problem with the server
(core/internal-error "DB is gone")

;; Specific for resources
(core/resource-not-found "Cannot find url")

;; generally reserved for JSON RPC structural errors
(core/invalid-request "I don't understand")

(core/->JSONRPCError -32123 "Application specific error" "explanation")
```

## Tools

Tools can be added/removed from primordial and live sessions. Notifications will be automatically sent to live session's clients, when tool list is modified.

```clojure

(defn get-weather [exchange arguments] ...)

(def tool (server/tool
            "get_current_weather"
            "Reports current weather based on the location"
            (server/obj-schema nil
                               {:location (server/str-schema "Location of interest" nil)}
                               ["location"])
            get-weather))

;; both modify the map and return the atom, you can ignore the return value if you wish
(server/add-tool session tool)
(server/remove-tool session "get_current_weather")
;; client is notified of the tool list change
```

The params schema is standard JSON Schema, it can be nested, `server` namespace has some functions to assist you:

```clojure
(server/obj-schema nil 
                   {:location (server/obj-schema 
                                "Location as coordiantes"
                                {:longitude (server/num-schema "Longitude" -180.0 180.0 nil nil nil)
                                 :latitude (server/num-schema "Latitude" -180.0 180.0 nil nil nil)}
                                ["longitude" "latitude"])}
                   ["location"])
```
<details>
<summary>Map as schema</summary>
Instead of using the provided functions you can just provide the schema as a map literal:
```clojure
{:description "Location as coordinates",
 :properties {:longitude {:description "Longitude", :minimum -180.0, :maximum 180.0, :type "number"},
              :latitude {:description "Latitude", :minimum -180.0, :maximum 180.0, :type "number"}},
 :required ["longitude" "latitude"],
 :type "object"}
```

Keys are converted to camelcase e.g. you can use `:exclusive-minimum` instead of `:exclusiveMinimum`.
</details>

### Tool return values

Result of tool operation is one or more Content objects. You can return a coll or a single object.

```clojure
;; text returns 
;; {"content" [{"text" "ABC", "type" "text"}]}
"ABC"
["ABC"]
;; with priority and audience
;; {"text" "ABC", "annotations" {"priority" 1.5, "audience" ["user"]}, "type" "text"}
(core/text-content "ABC" 1.5 :user)

;; other content types
;; {"annotations" {"priority" 1.5, "audience" ["user" "assistant"]}, "type" "image", "mimeType" "image/jpeg", "data" "AQ=="}
(core/image-content (byte-array [1]) "image/jpeg" 1.5 [:user :assistant])
;; {"type" "audio", "mimeType" "audio/mpeg", "data" "AQ=="}
(core/audio-content (byte-array [1]) "audio/mpeg")

;; embedded resource type, return anything that coerces ResourceResponse,
;; or explicitly call `c/embedded-content` if you want to add metadata

;; These all result in the same thing
;; {"resource" {"blob" "AQ==", "mimeType" "application/octet-stream"}, "type" "resource"}
(byte-array [1])
(ByteArrayInputStream. (byte-array [1]))
(core/embedded-content (byte-array [1]))
(core/resource (byte-array [1]) "application/octet-stream" nil)

;; These are both text resource
;; {"resource" {"text" "Text as resource", "mimeType" "text/plain"}, "type" "resource"}
(core/embedded-content "Text as resource")
(core/resource "Text as resource" "text/plain" nil)

;; With added URI
(core/embedded-content (c/resource "{\"a\": 1}" "text/json" "https://localhost/x.json"))

;; a mix
["ABC" (byte-array [1])]
```

Besides being able to return RPC errors as described above, tools have their own error mechanism:

```clojure
(core/tool-error "Text content")
```

## Prompts

Prompts can be added/removed from primordial and live sessions. Notifications will be automatically sent to live session's clients, when prompt list is modified.

```clojure

(defn code-review [exchange arguments]
  ...)

(def prompt (server/prompt "code_review" "Request Code Review"
                           {:code "Code to review"}
                           {}
                           code-review))

;; both modify the map and return the atom, you can ignore the return value if you wish
(server/add-prompt session prompt)
(server/remove-prompt session "code_review")
;; client is notified of the prompt list change
```

You can construct the prompt spec map manually.

### Prompt return values

Return of prompt handler should be a description and one or more of messages. Again, many shorthands are possible:

```clojure
;; produces {"description" "Our special review prompt"
;;           "messages" [{"content" {"text" "Here's the prompt", "type" "text"}}], }

;; the full response object
(core/prompt-resp "Our special review prompt" [(core/message nil (core/text-content "Here's the prompt"))])
;; you can skip wrapping it in core/message if you're not providing a role
(core/prompt-resp "Our special review prompt" [(core/text-content "Here's the prompt")])
;; for things like text you can skip wrapping in into context, and you can also skip vector if you have only 1 message
(core/prompt-resp "Our special review prompt" "Here's the prompt")
(core/prompt-resp "Our special review prompt" ["Here's the prompt"])
;; these all produce same thing

```

## Resources

Resources support is added by setting a resources handler.

The handler needs to satisfy `org.clojars.roklenarcic.mcp-server.resources/Resources` protocol.

```clojure
(server/set-resources-handler session resources)
```

In this Alpha version there is currently one implementation provided:
- [Lookup Map based implementation](doc/resource-lookup.md)

## Resource templates

Resource templates can be added/removed from primordial and live sessions.

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

Completions can be added/removed from primordial and live sessions.

```clojure
(defn completion [exchange name value]
  (core/completion-resp ["completion 1" "completion 2"]))

;; select ref/prompt or ref/resource and a name
(server/add-completion server "ref/prompt" "test-prompt" completion)
(server/remove-completion server "ref/prompt" "test-prompt")
```

You can set a general completion handler that will be used if no specific completion matches:

```clojure
(server/set-completion-handler
  session
  (fn [exchange ref-type ref-name name value] (core/completion-resp ["completion 1" "completion 2"])))
```

## Logging to client

```clojure
(core/log-msg exchange :info "tool.weather" "Fetching weather data from weather.com" {:credits-left 20000})
```

## Listing roots

Roots can be listed via `exchange`, returning a CompletableFuture. If client declares the ability to notify on root list
changes, then the roots are cached, with cached being cleared based on client's notification.

```clojure
(.thenApply (core/list-roots exchange) 
            (fn [roots]
              (mapv (fn [{:keys [name uri]}]
                      (println "Client root" name "at" uri))
                    roots)))
```

### Roots change callback

Register a roots change callback to a primordial or live session:

```clojure
(server/set-roots-changed-callback session (fn [exchange] ...))
```

## Sampling

You create a sampling request:

```clojure
;; first parameter is messages, which works the same as with prompt return value
;; it can be as simple as a string or as complex as a vector of Message objects that contain Content
(core/sampling-request "Simple sampling"
                       (core/model-preferences [{:name "claude-3"}] nil nil) 
                       nil 
                       nil)

(core/sampling-request [(core/message :user 
                                      (core/embedded-content
                                        (core/resource "Complex sampling param"
                                                       "text/plain"
                                                       "file://some-file.txt")
                                        4.5
                                        :assistant))]
                       (core/model-preferences [{:name "claude-3"}] nil nil)
                       "System prompt"
                       ;; max tokens
                       15555)
```

With sampling request in hand you can request a sampling:

```clojure
;; can return nil if client doesn't support sampling
(some-> (core/sampling exchange sampling-req)
        (.thenApply (fn [sampling-result]
                      ;; sampling result is something like:
                      ;; {:role "assistant",
                      ;; :content {:type "text",
                      ;;           :text "The capital of France is Paris."},
                      ;; :model "claude-3-sonnet-20240307",
                      ;; :stopReason "endTurn"}
                      ...)))
```

## Logging

Project uses clojure.tools.logging.

## Middleware

## Errors

Copyright (c) 2025 Rok Lenarčič