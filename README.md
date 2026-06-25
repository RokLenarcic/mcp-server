# MCP Server

A lightweight Clojure library for building MCP (Model Context Protocol) servers. This library prioritizes flexibility and minimal dependencies, allowing you to integrate with your existing technology stack.

The library is currently in alpha stage with features being added incrementally.

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/mcp-server.svg)](https://clojars.org/org.clojars.roklenarcic/mcp-server)

```clojure
;; deps.edn
org.clojars.roklenarcic/mcp-server {:mvn/version "0.3.54"}
```

## Table of Contents

- [Why Use This Library?](#why-use-this-library)
- [Current Alpha Limitations](#current-alpha-limitations)
- [Protocol Version Support](#protocol-version-support)
- [Quick Start](#quick-start)
- [Alternative: Manual Configuration](#alternative-manual-configuration)
- [Streamable HTTP Transport](#streamable-http-transport)
- [JSON Serializers](#json-serializers)
- [Key Namespaces](#key-namespaces)
- [Session Management](#session-management)
- [Execution Models](#execution-models)
- [Handler Functions](#handler-functions)
- [Error Handling](#error-handling)
- [Tools](#tools)
- [Prompts](#prompts)
- [Resources](#resources)
- [Resource Templates](#resource-templates)
- [Pagination](#pagination)
- [Tool Parameter Validation](#tool-parameter-validation)
- [Completions](#completions)
- [Titles and Metadata](#titles-and-metadata)
- [Client Communication](#client-communication)
- [Elicitation](#elicitation)
- [Request Metadata](#request-metadata)
- [Logging](#logging)
- [Middleware](#middleware)
- [Errors](#errors)
- [Authentication](#authentication)
- [License](#license)

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


## Protocol Version Support

The server advertises protocol version **2025-11-25** and accepts clients using either:

| Version | Status |
|---------|--------|
| `2025-11-25` | Fully supported (advertised) |
| `2025-06-18` | Accepted for backwards compatibility |

Clients requesting any other version will receive an `Invalid Request` error.

Features supported from the **2025-11-25** specification:

- Extended implementation metadata (title, description, icons, websiteUrl on serverInfo)
- Icons on tools, prompts, resources, and resource templates
- URL-mode elicitation (`elicitation/create` with `mode: "url"`)
- `notifications/elicitation/complete` notification
- Audio content type

The following features from the **2025-11-25** specification are **not yet implemented**:

- **Tasks** — The task state machine for long-running operations (`tasks/get`, `tasks/result`, `tasks/list`, `tasks/cancel`, task-augmented requests) is not supported. This includes the `execution.taskSupport` field on tool definitions.
- **Authorization** — The OAuth-based authorization framework for HTTP transports is not built-in. You can implement your own auth middleware around the Ring handler.
- **URLElicitationRequiredError** — The `-32042` error code that signals a URL-mode elicitation is required before a request can be processed is not emitted automatically. Handlers can return it manually via `core/->JSONRPCError`.
- **JSON Schema dialect enforcement** — The spec requires validating schemas according to their declared `$schema` dialect and rejecting unsupported dialects. The library passes schemas through as-is without dialect-level validation.

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

## Streamable HTTP Transport

For HTTP-based communication, use `ring-handler` from the HTTP transport namespace. This implements the MCP Streamable HTTP transport on a single endpoint that handles three methods: `POST` for client to server JSON-RPC, `GET` for a server to client SSE stream, and `DELETE` to terminate a session. The result is a standard Ring handler that you can mount in any Ring-compatible HTTP server:

```clojure
(ns example.http-server
  (:require [org.clojars.roklenarcic.mcp-server.json.charred :as json]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.server.http :as http]))

(defn start []
  (let [session (-> (server/make-session
                      (server/server-info "My Service" "1.0.0" "Description")
                      (json/serde {})
                      {})
                    (server/add-tool my-tool))
        sessions (http/memory-sessions-store)
        handler (http/ring-handler session sessions
                                   {:allowed-origins ["https://example.com"]
                                    :client-req-timeout-ms 120000})]
    ;; Mount `handler` in your preferred Ring-compatible HTTP server
    ;; (ring-jetty, http-kit, etc.)
    handler))
```

The `ring-handler` supports both synchronous and asynchronous Ring operation. The `memory-sessions-store` provides an in-memory session store backed by `ConcurrentHashMap`. You can implement the `Sessions` protocol for custom session storage (e.g., Redis-backed).

Options:
- `:allowed-origins` — collection of allowed Origin headers (`nil` permits all origins)
- `:client-req-timeout-ms` — timeout for client requests in milliseconds (default: 120000)
- `:sse-queue-capacity` — bounded buffer for server-to-client messages produced while no SSE stream is attached (default: 1024)
- `:sse-replay-capacity` — bounded replay buffer for `Last-Event-ID` resumability on GET reconnect (default: same as `:sse-queue-capacity`)

### Synchronous vs asynchronous Ring

The GET SSE channel behaves differently depending on which Ring arity your adapter invokes:

- **Synchronous (1-arity)**: the GET delivers any pending messages and detaches. Subsequent server-initiated requests/notifications are queued and delivered on the next `GET /mcp`.
- **Asynchronous (3-arity)**: the SSE stream stays open and live server-initiated messages are written to it immediately, until the client disconnects or `DELETE /mcp` is called.

Use an async Ring adapter for live server-to-client streaming over a long-lived SSE connection. Use the sync adapter only if your deployment polls `GET /mcp` or does not initiate server-side requests. Reconnecting clients may supply the standard `Last-Event-ID` header on `GET /mcp` to resume from the per-session replay buffer.

### Protocol headers

After the initialize handshake the server returns a `Mcp-Session-Id` header. Clients **must** include that header on every subsequent `POST`, `GET`, and `DELETE` request, and **should** include `MCP-Protocol-Version: 2025-06-18` on non-initialize requests. `POST` requests must declare `Content-Type: application/json` and `Accept: application/json, text/event-stream`; `GET` requests must declare `Accept: text/event-stream`. Requests carrying an unknown session id receive `404 Not Found`; mismatched headers return `400 Bad Request` or `406 Not Acceptable`.

## JSON Serializers

You can write your own integration, by extending the `org.clojars.roklenarcic.mcp-server.json-rpc/JSONSerialization` protocol, but there are many available already:

- org.clojars.roklenarcic.mcp-server.json.babashka/serde
- org.clojars.roklenarcic.mcp-server.json.charred/serde
- org.clojars.roklenarcic.mcp-server.json.cheshire/serde
- org.clojars.roklenarcic.mcp-server.json.clj-data/serde
- org.clojars.roklenarcic.mcp-server.json.jsonista/serde

Each requires its own dependency on the classpath. Some guidance on choosing:

- **Charred** — fastest option, good default for JVM Clojure
- **Cheshire** — most widely used in the Clojure ecosystem
- **Jsonista** — fast, Metosin ecosystem
- **Babashka JSON** — compatible with Babashka
- **clj-data** (`clojure.data.json`) — no extra dependencies beyond Clojure contrib

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

`server/tool` accepts the following optional keyword arguments (MCP 2025-06-18):

- `:title` — human-readable display name; clients SHOULD prefer it over `name`.
- `:output-schema` — JSON Schema describing the structure of `:structuredContent`
  the tool returns. See [Structured Tool Output](#structured-tool-output) below.
- `:_meta` — map of arbitrary metadata to attach to the tool. Keys under
  `:_meta` are preserved verbatim on the wire (no kebab→camelCase
  transformation). See [Titles and Metadata](#titles-and-metadata).

```clojure
(server/tool
  "get_current_weather"
  "Reports current weather based on the location"
  schema
  get-weather
  :title "Current Weather"
  :_meta {"com.example/cost-tier" "premium"})
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

Tool handlers can also return resource links — lightweight pointers to
resources the client may fetch separately, instead of embedding the body
inline:

```clojure
;; Minimal resource link
(core/resource-link "file:///report.pdf" "report")

;; With title, description, MIME type, priority and audience
(core/resource-link "file:///report.pdf" "report"
                    :title "Q4 Report"
                    :description "Quarterly report for review"
                    :mime-type "application/pdf"
                    :priority 4.5
                    :audience [:user])
```

Tools can also return specific error objects:

```clojure
(core/tool-error "Something went wrong")
```

### Structured Tool Output

When a tool declares an `:output-schema`, MCP 2025-06-18 lets the handler
return both displayable content and a structured payload. Use
`core/tool-result` for that:

```clojure
(def weather-tool
  (server/tool
    "get_current_weather"
    "Reports current weather based on the location"
    location-schema
    (fn [exchange {:keys [location]}]
      (let [result {:location location
                    :temperature 18
                    :condition "sunny"}]
        (core/tool-result
          ;; Displayable content the user/LLM sees
          (format "Weather at %s: %s, %d°C"
                  location (:condition result) (:temperature result))
          ;; Structured payload conforming to :output-schema
          result
          ;; Optional :_meta on the result envelope
          :_meta {"com.example/source" "weather.com"})))
    :output-schema (server/obj-schema
                     "Weather observation"
                     {:location (server/str-schema "Location" nil)
                      :temperature (server/num-schema "Temperature in °C" -100 100 nil nil nil)
                      :condition (server/str-schema "Condition" nil)}
                     ["location" "temperature" "condition"])))
```

Plain (non-structured) handlers can return the same content shapes shown
above; you only need `core/tool-result` when you also want to send
`:structuredContent` or attach `:_meta` to the result envelope.

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

`server/prompt` accepts the following optional keyword arguments
(MCP 2025-06-18):

- `:title` — human-readable display name; clients SHOULD prefer it over `name`.
- `:_meta` — map of arbitrary metadata to attach to the prompt. Keys under
  `:_meta` are preserved verbatim on the wire.

```clojure
(server/prompt "code_review" "Request Code Review"
               {:code "Code to review"}
               {}
               code-review
               :title "Code Review"
               :_meta {"com.example/category" "engineering"})
```

### Prompt Return Values

Prompt handlers return a description and one or more messages:

```clojure
;; Full response object
(core/prompt-resp "Our special review prompt"
                  [(core/message nil (core/text-content "Here's the prompt"))])

;; Simplified forms (all equivalent)
(core/prompt-resp "Our special review prompt" [(core/text-content "Here's the prompt")])
;; For simple text content you can skip wrapping in content objects, and you can skip the vector for a single message
(core/prompt-resp "Our special review prompt" "Here's the prompt")
(core/prompt-resp "Our special review prompt" ["Here's the prompt"])
;; these all produce same thing

;; Attach :_meta to the prompt result envelope (MCP 2025-06-18)
(core/prompt-resp "Our special review prompt"
                  "Here's the prompt"
                  :_meta {"com.example/version" "v2"})
```

## Resources

Resource support is added by setting a resource handler that implements the `Resources` protocol:

```clojure
(server/set-resources-handler session resources)
```

Currently, one implementation is provided:
- [Lookup Map Resources](doc/resource-lookup.md)

### Resource Descriptions

Use `core/resource-desc` to declare a resource entry. MCP 2025-06-18 adds
two optional fields:

- `:title` — human-readable display name; clients SHOULD prefer it over `name`.
- `:_meta` — map of arbitrary metadata preserved verbatim on the wire.

```clojure
(core/resource-desc "file:///report.pdf"
                    "report"
                    "Quarterly report"
                    "application/pdf"
                    nil
                    :title "Q4 Report"
                    :_meta {"com.example/owner" "finance"})
```

### Resource Read Results

A `resources/read` handler can return a `ResourceResponse` (or a
collection of them) directly. If you also need to attach `:_meta` to the
read-result envelope itself (MCP 2025-06-18), wrap the contents with
`core/resource-read-result`:

```clojure
(core/resource-read-result
  (core/resource "Hello" "text/plain" "file:///hello.txt")
  :_meta {"com.example/cache" "hit"})

;; Or wrap a collection of ResourceResponses
(core/resource-read-result
  [(core/resource part-1 "text/plain" uri-1)
   (core/resource part-2 "text/plain" uri-2)]
  :_meta {"com.example/source" "db"})
```

## Resource Templates

Resource templates define URI patterns for dynamically resolved resources. They can be added or removed from sessions:

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

The annotations vector allows attaching metadata to the template:
- `:audience` — who the resource is intended for (`:user`, `:assistant`, or both)
- `:priority` — numeric priority hint for ordering (higher = more important)

`server/add-resource-template` also accepts the following optional keyword
arguments (MCP 2025-06-18):

- `:title` — human-readable display name; clients SHOULD prefer it over `name`.
- `:_meta` — map of arbitrary metadata preserved verbatim on the wire.

```clojure
(server/add-resource-template session
                              "file:///{temp}.txt"
                              "general_file"
                              "General file template"
                              "text/plain"
                              nil
                              :title "General File"
                              :_meta {"com.example/category" "filesystem"})
```

## Pagination

The `tools/list`, `prompts/list`, `resources/list`, and `resources/templates/list` operations support cursor-based pagination. Pagination is **opt-in** and disabled by default.

To enable it, set a page size on the session using `server/set-page-size`:

```clojure
(-> (server/make-session ...)
    (server/set-page-size 50)
    (server/add-tool my-tool))
```

When `::mcp/page-size` is set, list responses include a `:nextCursor` field whenever more items follow. Pass that value as the `cursor` parameter in the next request. Items are returned sorted alphabetically by name (or URI for resources). A stale or unknown cursor silently returns the first page.

## Tool Parameter Validation

The library can validate tool arguments against each tool's `:inputSchema` before the handler is invoked. Validation is **opt-in** and disabled by default.

The schema is the third argument to `server/tool` (see [Tool Schemas](#tool-schemas)). No special configuration is needed on the tool itself — the same schema that is advertised to clients via `tools/list` is also used for server-side validation.

You bring your own JSON Schema validator. Three adapters are provided out of the box (one per supported Java library). All adapters receive the session's `JSONSerialization` instance at validation time and use it to serialise the schema and arguments to JSON strings — no separate JSON configuration required.

### Using networknt/json-schema-validator (recommended)

```clojure
;; deps.edn
{com.networknt/json-schema-validator {:mvn/version "1.5.1"}}
```

```clojure
(require '[org.clojars.roklenarcic.mcp-server.schema.networknt :as nnt])

(-> (server/make-session ...)
    (server/set-params-validator (nnt/validator))        ; Draft-07 by default
    (server/set-params-validator (nnt/validator {:draft :draft-2020-12}))  ; or specify draft
    (server/add-tool my-tool))
```

`nnt/validator` options:

| Option | Default | Description |
|---|---|---|
| `:draft` | `:draft-07` | Schema draft. Supported: `:draft-04` `:draft-06` `:draft-07` `:draft-2019-09` `:draft-2020-12`. |

### Using json-sKema

```clojure
;; deps.edn
{com.github.erosb/json-sKema {:mvn/version "0.18.0"}}
```

```clojure
(require '[org.clojars.roklenarcic.mcp-server.schema.json-skema :as skema])

(-> (server/make-session ...)
    (server/set-params-validator (skema/validator))
    (server/add-tool my-tool))
```

### Using harrel/json-schema

```clojure
;; deps.edn
{dev.harrel/json-schema {:mvn/version "1.5.0"}}
```

```clojure
(require '[org.clojars.roklenarcic.mcp-server.schema.harrel :as harrel])

(-> (server/make-session ...)
    (server/set-params-validator (harrel/validator))
    (server/add-tool my-tool))
```

### How validation works

When a `tools/call` request arrives:

1. If no validator is set, the tool handler is called immediately (no change in behaviour).
2. If a validator is set and the tool has an `:inputSchema`, the arguments are validated against the schema.
3. If validation **passes** (validator returns `nil`), the tool handler is invoked as normal.
4. If validation **fails**, an `invalid-params` JSON-RPC error is returned to the client and the handler is **never called**. The error message lists all validation errors joined by `"; "`.

Tools that have no `:inputSchema` are never validated even when a validator is configured.

To remove the validator at runtime, pass `nil`:

```clojure
(server/set-params-validator session nil)
```

### Custom validators

Any object satisfying the `SchemaValidator` protocol works. The `json-impl` parameter is the session's `JSONSerialization` instance — use it to serialise to JSON strings if needed, or inspect the Clojure data directly:

```clojure
(require '[org.clojars.roklenarcic.mcp-server.protocol :as p])

(def my-validator
  (reify p/SchemaValidator
    (-validate [_ json-impl schema data]
      ;; return nil if valid, or a seq of error strings if invalid
      (when-not (contains? data :required-field)
        ["required-field is missing"]))))

(server/set-params-validator session my-validator)
```

## Completions

Completions provide autocomplete functionality:

```clojure
(defn completion [exchange name value context]
  (core/completion-resp ["completion 1" "completion 2"]))

;; Add completion for specific prompts or resources
(server/add-completion session "ref/prompt" "test-prompt" completion)
(server/remove-completion session "ref/prompt" "test-prompt")
```

You can also set a general completion handler for unmatched requests:

```clojure
(server/set-completion-handler
  session
  (fn [exchange ref-type ref-name name value context]
    (core/completion-resp ["completion 1" "completion 2"])))
```

### Completion Context

MCP 2025-06-18 lets clients send already-resolved arguments along with a
completion request, so the server can produce context-aware suggestions.
The trailing `context` argument carries that map (or `nil` when the
client did not include one). It is shaped like
`{:arguments {:arg-name "value" ...}}`:

```clojure
(defn city-completion [exchange name value context]
  (let [country (get-in context [:arguments :country])]
    (core/completion-resp (cities-in country value))))
```

## Titles and Metadata

MCP 2025-06-18 introduces two cross-cutting fields that appear on tools,
prompts, resources, resource templates, and a few result envelopes:

- `:title` — human-readable display name. Clients SHOULD prefer it over the
  programmatic `name` when rendering UI. The programmatic `name` remains
  the identifier servers use for dispatch.
- `:_meta` — map of arbitrary metadata. Use it to attach
  application-specific information (categories, cost tiers, source,
  cache hints, etc.) without colliding with reserved spec fields.

### Where you can attach them

| Spec item / envelope             | Helper                              | `:title` | `:_meta` |
| -------------------------------- | ----------------------------------- | -------- | -------- |
| Tool definition                  | `server/tool`                       | yes      | yes      |
| Prompt definition                | `server/prompt`                     | yes      | yes      |
| Resource description             | `core/resource-desc`                | yes      | yes      |
| Resource template                | `server/add-resource-template`      | yes      | yes      |
| Resource link content            | `core/resource-link`                | yes      | n/a      |
| Tool result envelope             | `core/tool-result`                  | n/a      | yes      |
| Prompt result envelope           | `core/prompt-resp`                  | n/a      | yes      |
| Resource read result envelope    | `core/resource-read-result`         | n/a      | yes      |

### Verbatim wire encoding for `:_meta`

Most spec keys go through a kebab→camelCase rewrite when written to the
wire (e.g. `:exclusive-minimum` becomes `:exclusiveMinimum`). The
contents of `:_meta` are deliberately **not** rewritten — the
serializer copies them through untouched. This lets you put reverse-DNS
identifiers, snake_case keys, or any other custom-formatted keys into
`:_meta` and have them appear exactly as written:

```clojure
{:_meta {"com.example/cost-tier" "premium"
         "com.example/cache_hint" "skip"
         :keep-as-is "verbatim"}}
```

The same rule applies on the way in: `core/request-_meta` (see
[Request Metadata](#request-metadata)) returns the inbound `:_meta` map
verbatim.

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

### Elicitation

Elicitation lets a server ask the user (via the client) for structured
input mid-flight. Unlike sampling, the client doesn't generate the
response with an LLM — it presents the request to the user and returns
their answer. The schema is constrained: a flat object schema with
primitive properties.

```clojure
;; Returns CompletableFuture, or nil if client doesn't support elicitation
(some-> (core/elicitation
          exchange
          "Please confirm before deleting these files:"
          (server/obj-schema
            "Confirmation"
            {:confirm  (server/bool-schema "Confirm deletion")
             :reason   (server/str-schema "Optional comment" nil)}
            ["confirm"]))
        (.thenApply (fn [{:keys [action content]}]
                      ;; action is one of "accept", "decline", "cancel"
                      ;; content is the user's response (a map) when action = "accept"
                      (case action
                        "accept"  (do-the-deletion content)
                        "decline" (log-decline content)
                        "cancel"  (log-cancel)))))

;; With progress callback
(some-> (core/elicitation exchange message json-schema
                          (fn [progress]
                            (println "Elicitation progress:" (:message progress))))
        (.thenApply ...))
```

The result envelope contains `:action` (one of `"accept"`, `"decline"`,
`"cancel"`) and, when accepted, `:content` carrying the user's response
matching the requested schema.

### Request Metadata

Every MCP request can carry a top-level `:_meta` map (MCP 2025-06-18).
Handlers can read it with `core/request-_meta`:

```clojure
(defn my-tool [exchange args]
  (let [meta (core/request-_meta exchange)
        trace-id (get meta "com.example/trace-id")]
    (when trace-id
      (log/info "Tool called with trace" trace-id))
    ...))
```

The map is exposed verbatim — keys are not transformed. Use it to read
correlation IDs, feature flags, or any other request-scoped metadata the
client decides to attach.

`request-_meta` returns `nil` when the client did not include `:_meta`.

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
Dispatch handlers and middleware-wrapped handlers are called with three arguments:

- `rpc-session`: the session atom
- `req-meta`: request-specific metadata
- `params`: parsed JSON-RPC method params

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
  (fn check-credentials [rpc-session req-meta params]
    (if (:user-id @rpc-session)
      (handler rpc-session req-meta params)
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

The easiest way you can integrate your Authentication solution with this library is via request metadata.

This library provides a Ring handler. You can wrap handler with middleware to block all unauthorized requests to it.

For HTTP transports, `req-meta` starts as the Ring request map, so transport-level authentication middleware can attach
application-specific data to that map before JSON-RPC dispatch. For stream transports, `req-meta` starts as `nil`.

The JSON-RPC layer also adds namespaced metadata to `req-meta`, such as `::mcp/request-id` when a request id is present.
When a `RequestExchange` is created for a tool, prompt, resource, or completion handler, request `params._meta` is exposed
as `::mcp/request-_meta` and can be read with `core/request-_meta`.

## License

This project is licensed under the [MIT License](LICENSE).

Copyright (c) 2025 Rok Lenarčič
