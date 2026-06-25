(ns org.clojars.roklenarcic.mcp-server.core
  "This namespace provides the core API for building MCP (Model Context Protocol) servers.
  It includes protocol definitions, helper functions for creating various MCP objects,
  and the main abstractions for request handling."
  (:require [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as rpc]
            [org.clojars.roklenarcic.mcp-server.util :refer [map-of ?assoc]])
  (:import (java.io InputStream)
           (java.util.concurrent CompletableFuture)))

(defprotocol RequestExchange
  "An Exchange scoped to the request - the main interface for MCP handlers."
  (req-meta [this] "Returns request metadata")
  (client-spec [this]
    "Returns data about client capabilities and information.
     Returns a map with :info and :capabilities keys.")
  (get-session [this] "Returns the session data atom.")
  (log-msg [this level logger msg data]
    "Logs a message to the client if logging is enabled.
     
     Parameters:
     - level: one of :debug, :info, :notice, :warning, :error, :critical, :alert, :emergency
     - logger: string identifying the logger
     - msg: log message string
     - data: optional additional data to include")
  (^CompletableFuture list-roots [this] [this progress-callback]
    "Lists the client's root directories/URIs.

     If progress-callback is supplied, it will be called when client reports progress.

     Returns a CompletableFuture containing a vector of root objects.")
  (^CompletableFuture sampling [this sampling-request] [this sampling-request progress-callback]
    "Requests the client to perform LLM sampling/completion.
     Returns CompletableFuture with result, or nil if client doesn't support sampling.

     If progress-callback is supplied, it will be called when client reports progress.
     
     sampling-request should be created with the sampling-request function.")
  (^CompletableFuture elicitation [this message json-schema] [this message json-schema progress-callback]
    "Requests structured input from the user via the client (MCP elicitation/create, form mode).
     Returns CompletableFuture with result, or nil if client doesn't support form-mode elicitation.

     Parameters:
     - message: human-readable prompt presented to the user
     - json-schema: JSON Schema (a map) describing the expected response shape;
       MCP restricts this to a flat object schema with primitive properties

     If progress-callback is supplied, it will be called when client reports progress.")
  (^CompletableFuture elicitation-url [this message url elicitation-id]
                                      [this message url elicitation-id progress-callback]
    "Requests the client to open a URL for out-of-band interaction (MCP 2025-11-25 URL mode elicitation).
     Returns CompletableFuture with result, or nil if client doesn't support URL-mode elicitation.

     Parameters:
     - message: human-readable message explaining why the interaction is needed
     - url: the URL that the user should navigate to
     - elicitation-id: a unique identifier for the elicitation

     If progress-callback is supplied, it will be called when client reports progress.")
  (report-progress [this msg]
    "Reports progress to the client, msg is a map with :progress :total :message keys")
  (^CompletableFuture req-cancelled-future [this] "Returns CompletableFuture that is completed with cancellation message if the request is cancelled"))

(extend-protocol p/ResourceResponse
  String
  (-res-body [this] this)
  (-res-mime [this] "text/plain")
  (-res-uri [this] nil)
  InputStream
  (-res-body [this] this)
  (-res-mime [this] "application/octet-stream")
  (-res-uri [this] nil))

(extend-type (class (make-array Byte/TYPE 0)) p/ResourceResponse
             (-res-body [this] this)
             (-res-mime [this] "application/octet-stream")
             (-res-uri [this] nil))

(defrecord JSONRPCError [code message data])

(defn invalid-request
  "Create an invalid request error response.
   
   Parameters:
   - data: optional error data
   - message: optional custom error message (defaults to 'Invalid Request')"
  ([data] (invalid-request data "Invalid Request"))
  ([data message]
   (->JSONRPCError rpc/INVALID_REQUEST message data)))

(defn invalid-params
  "Create an invalid params error response.
   
   Parameters:
   - data: optional error data
   - message: optional custom error message (defaults to 'Invalid Params')"
  ([data] (invalid-params data "Invalid Params"))
  ([data message]
   (->JSONRPCError rpc/INVALID_PARAMS message data)))

(defn internal-error
  "Create an internal error response.
   
   Parameters:
   - data: optional error data
   - message: error message describing the internal error"
  [data message]
  (->JSONRPCError rpc/INTERNAL_ERROR message data))

(defn resource-not-found
  "Creates a resource not found error message.
   
   Parameters:
   - uri: the URI of the resource that was not found"
  [uri]
  (->JSONRPCError rpc/RESOURCE_NOT_FOUND "Resource Not Found" uri))

(defn resource
  "Create a resource content for Get Resource response.
   
   Parameters:
   - body: the resource content (String, byte array, or InputStream)
   - mime-type: MIME type of the resource (optional, will be inferred if nil)
   - uri: URI of the resource (optional, request URI will be used if nil)
   
   If body is a String, a text resource will be generated.
   If body is a byte array or InputStream, a binary resource is created."
  [body mime-type uri]
  (reify p/ResourceResponse
    (-res-body [this] (p/-res-body body))
    (-res-mime [this] (or mime-type (p/-res-mime body)))
    (-res-uri [this] uri)))

(defn resource-desc
  "Create a resource description for List Resources operation.

   Parameters:
   - uri: URI of the resource
   - name: programmatic name of the resource
   - description: description of the resource
   - mime-type: MIME type of the resource
   - annotations: optional metadata annotations

   Optional keyword arguments (MCP 2025-06-18):
   - :title - human-readable display name; clients SHOULD prefer it over name
     when present
   - :_meta - map of arbitrary metadata to attach to this resource. Keys
     under :_meta are preserved verbatim on the wire (no kebab→camelCase
     transformation); use this to attach reverse-DNS or otherwise
     custom-formatted identifiers.

   Optional keyword arguments (MCP 2025-11-25):
   - :icons - vector of icon maps for display in user interfaces. Each map
     has :src (required), and optionally :mime-type, :sizes, :theme."
  [uri name description mime-type annotations & {:keys [title _meta icons]}]
  (-> (map-of uri name description mime-type annotations)
      (?assoc :title title :_meta _meta :icons icons)))

(defn audio-content
  "Create audio content for Tools and Prompts.
   
   Parameters:
   - data: audio data (byte array or InputStream)
   - mime-type: MIME type of the audio (e.g., 'audio/wav', 'audio/mp3')
   - priority: optional priority value (double)
   - audience: optional audience vector (:user, :assistant, or both)"
  ([data mime-type]
   (audio-content data mime-type nil nil))
  ([data mime-type priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/AudioContent
     (-aud-mime-type [this] mime-type)
     (-aud-data [this] data))))

(defn image-content
  "Create image content for Tools and Prompts.
   
   Parameters:
   - data: image data (byte array or InputStream)
   - mime-type: MIME type of the image (e.g., 'image/png', 'image/jpeg')
   - priority: optional priority value (double)
   - audience: optional audience vector (:user, :assistant, or both)"
  ([data mime-type]
   (image-content data mime-type nil nil))
  ([data mime-type priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/ImageContent
     (-con-mime-type [this] mime-type)
     (-con-data [this] data))))

(defn embedded-content
  "Create embedded resource content for Tools and Prompts.
   
   Parameters:
   - resource: ResourceResponse protocol object (String, byte[], or InputStream)
   - priority: optional priority value (double)
   - audience: optional audience vector (:user, :assistant, or both)"
  ([resource] (embedded-content resource nil nil))
  ([resource priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/ResourceResponse
     (-res-body [this] (p/-res-body resource))
     (-res-mime [this] (p/-res-mime resource))
     (-res-uri [this] (p/-res-uri resource)))))

(defn resource-link
  "Create a resource link content piece for Tools and Prompts (MCP 2025-06-18).

   A resource link is a lightweight pointer to a resource the client may
   fetch separately, as opposed to embedded-content which carries the body.

   Parameters:
   - uri: resource URI (string, required)
   - name: programmatic name of the resource (string, required)

   Optional keyword arguments:
   - :title - human-readable title
   - :description - human-readable description
   - :mime-type - MIME type of the linked resource
   - :priority - priority value (double)
   - :audience - audience keyword or vector of keywords (:user, :assistant)"
  [uri name & {:keys [title description mime-type priority audience]}]
  (reify
    p/Content
    (-con-priority [this] priority)
    (-con-audience [this] audience)
    p/ResourceLinkContent
    (-link-uri [this] uri)
    (-link-name [this] name)
    (-link-title [this] title)
    (-link-description [this] description)
    (-link-mime-type [this] mime-type)))

(defn text-content
  "Create text content for Tools and Prompts.
   
   Parameters:
   - text: the text content (string)
   - priority: optional priority value (double)
   - audience: optional audience vector (:user, :assistant, or both)"
  ([text] (text-content text nil nil))
  ([text priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/TextContent
     (-con-text [this] text))))

(defn message
  "Create a message with role and content.
   
   Parameters:
   - role: message role (:user or :assistant)
   - content: message content (image-content, embedded-content, or text-content)"
  [role content]
  (reify p/Message
    (-msg-role [this] role)
    (-msg-content [this] content)))

(defn prompt-resp
  "Create a prompt response with description and messages.

   Parameters:
   - description: description of the prompt response
   - messages: one or more prompt messages (single messages are automatically wrapped into a vector)

   A message can be a core/Message, or just a core/Content object (which becomes a Message with nil role).

   Optional keyword arguments:
   - :_meta - map of arbitrary metadata to attach to the prompt result
     envelope. Keys under :_meta are preserved verbatim on the wire (no
     kebab→camelCase transformation); use this to attach reverse-DNS or
     otherwise custom-formatted identifiers."
  [description messages & {:keys [_meta]}]
  (reify p/PromptResponse
    (-prompt-desc [this] description)
    (-prompt-msgs [this] messages)
    (-prompt-meta [this] _meta)))

(defn completion-resp
  "Create a completion response. Protocol limits responses to 100 items.
   
   Parameters:
   - values: collection of completion strings
   - total: integer of total number of all items (optional)
   - has-more?: boolean indicating if there are more than 100 items
   
   1-arg arity assumes that the values collection contains all available items."
  ([values] (completion-resp values (count values) (< 100 (count values))))
  ([values total] (completion-resp values total (when total (< (count values) total))))
  ([values total has-more?]
   {:values (take 100 values)
    :total total
    :hasMore has-more?}))

(defn request-_meta
  "Returns the inbound :_meta map from the current MCP request, or nil if
   the client did not include one (MCP 2025-06-18).

   Per the MCP spec, every request can carry a top-level :_meta map. The
   server preserves user-defined keys verbatim (no kebab→camelCase
   transformation) so reverse-DNS or other custom-formatted identifiers
   are exposed exactly as the client sent them.

   Note: this is the request-level :_meta, not a result envelope. Use
   tool-result, prompt-resp, etc. with their :_meta kwarg to attach
   metadata on the way back out.

   Parameters:
   - exchange: the RequestExchange passed to any handler

   Returns: the :_meta map verbatim, or nil if absent."
  [exchange]
  (-> (req-meta exchange) ::mcp/request-_meta))

(defn tool-error
  "Create a tool error response.
   
   Parameters:
   - content: one content object or a collection of them describing the error"
  [content]
  (reify p/ToolErrorResponse
    (-err-contents [this] content)))

(defn tool-result
  "Create a tool result with both displayable content and structured content
   (MCP 2025-06-18).

   Use this when a tool's :output-schema is defined and the handler wants to
   return data conforming to that schema in addition to displayable content.

   Parameters:
   - content: one content object or a collection of them (displayable side)
   - structured-content: map conforming to the tool's :output-schema

   Optional keyword arguments:
   - :_meta - map of arbitrary metadata to attach to the tool result
     envelope. Keys under :_meta are preserved verbatim on the wire (no
     kebab→camelCase transformation); use this to attach reverse-DNS or
     otherwise custom-formatted identifiers."
  [content structured-content & {:keys [_meta]}]
  (reify p/ToolResult
    (-result-content [this] content)
    (-result-structured [this] structured-content)
    (-result-meta [this] _meta)))

(defn resource-read-result
  "Create a resources/read result envelope that carries :_meta (MCP
   2025-06-18).

   Most resource handlers can return a ResourceResponse (or a collection
   of them) directly. Use this wrapper only when you need to attach
   metadata to the result envelope itself.

   Parameters:
   - contents: one ResourceResponse or a collection of them

   Required keyword arguments:
   - :_meta - map of arbitrary metadata to attach to the read-resource
     envelope. Keys under :_meta are preserved verbatim on the wire (no
     kebab→camelCase transformation)."
  [contents & {:keys [_meta]}]
  (reify p/ResourceReadResult
    (-read-contents [this] contents)
    (-read-meta [this] _meta)))

(defn model-preferences
  "Create model preferences for a sampling request.
   
   Parameters:
   - hints: vector of model hints (e.g., [{:name \"claude-3-sonnet\"}])
   - intelligence-priority: priority for intelligence vs speed (double)
   - speed-priority: priority for speed vs intelligence (double)"
  [hints intelligence-priority speed-priority]
  (map-of hints intelligence-priority speed-priority))

(defn sampling-request
  "Create a sampling request for LLM completion.
   
   Parameters:
   - messages: one or more core/Message objects or core/Content objects
   - model-preferences: map created by model-preferences function
   - system-prompt: string containing the system prompt
   - max-tokens: maximum number of tokens to generate"
  [messages model-preferences system-prompt max-tokens]
  (map-of messages model-preferences system-prompt max-tokens))