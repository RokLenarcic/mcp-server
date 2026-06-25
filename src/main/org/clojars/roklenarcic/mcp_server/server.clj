(ns org.clojars.roklenarcic.mcp-server.server
  "This namespace provides the main server implementation for MCP (Model Context Protocol).
   It includes session management, handler registration, and server lifecycle management."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.server.streams :as streams]
            [org.clojars.roklenarcic.mcp-server.handler.init :as h.init]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.handler.logging :as h.logging]
            [org.clojars.roklenarcic.mcp-server.handler.completions :as h.completions]
            [org.clojars.roklenarcic.mcp-server.handler.prompts :as h.prompts]
            [org.clojars.roklenarcic.mcp-server.handler.resources :as h.resources]
            [org.clojars.roklenarcic.mcp-server.handler.tools :as h.tools]
            [org.clojars.roklenarcic.mcp-server.util :as util :refer [map-of ?assoc]])
  (:import (java.util.concurrent Executors)))

(def mcp-functions
  "Core MCP function dispatch table mapping method names to handlers."
  (-> {"ping" (fn [_ _ _] {})  ; Basic ping handler for connectivity testing
       :client-resp rpc/handle-client-response  ; Handle responses from client
       "logging/setLevel" h.logging/logging-set-level  ; Set logging level
       "notifications/roots/list_changed" handler/handle-changed-root  ; Handle root changes
       "notifications/cancelled" handler/handle-request-cancelled
       "notifications/progress" handler/handle-progress  ; Handle progress notification
       "completion/complete" h.completions/handler}  ; Handle completion requests
      h.init/add-init-handlers      ; Add initialization handlers
      h.resources/add-resources-handlers  ; Add resource management handlers
      h.prompts/add-prompt-handlers       ; Add prompt handlers
      h.tools/add-tool-handlers))         ; Add tool handlers

(defn add-tool
  "Adds a tool to a session and notifies the client.
   
   Parameters:
   - session: the session atom
   - tool-spec: tool specification map created with the 'tool' function
   
   Returns the session atom."
  [session tool-spec]
  (log/info "Adding tool:" (:name tool-spec))
  (swap! session update-in [::mcp/handlers :tools] assoc (:name tool-spec) tool-spec)
  session)

(defn remove-tool
  "Removes a tool from a session and notifies the client.
   
   Parameters:
   - session: the session atom
   - tool-name: name of the tool to remove
   
   Returns the session atom."
  [session tool-name]
  (log/info "Removing tool:" tool-name)
  (swap! session update-in [::mcp/handlers :tools] dissoc tool-name)
  session)

(defn add-prompt
  "Adds a prompt to a session and notifies the client.
   
   Parameters:
   - session: the session atom
   - prompt-spec: prompt specification map created with the 'prompt' function
   
   Returns the session atom."
  [session prompt-spec]
  (log/info "Adding prompt:" (:name prompt-spec))
  (swap! session update-in [::mcp/handlers :prompts] assoc (:name prompt-spec) (h.prompts/->prompt prompt-spec))
  session)

(defn remove-prompt
  "Removes a prompt from a session and notifies the client.
   
   Parameters:
   - session: the session atom
   - prompt-name: name of the prompt to remove
   
   Returns the updated session atom."
  [session prompt-name]
  (log/info "Removing prompt:" prompt-name)
  (swap! session update-in [::mcp/handlers :prompts] dissoc prompt-name)
  session)

(defn set-roots-changed-callback
  "Sets (or unsets if nil) a callback that is called whenever the client's roots change.
   
   Parameters:
   - session: the session atom
   - callback: function (fn [exchange] ...) to call on root changes, or nil to unset
   
   Returns the session atom."
  [session callback]
  (if callback
    (do (log/info "Setting roots changed callback")
        (swap! session update ::mcp/handlers assoc :roots-changed-callback callback))
    (do (log/info "Unsetting roots changed callback")
        (swap! session update ::mcp/handlers dissoc :roots-changed-callback)))
  session)

(defn add-resource-template
  "Add a resource template to a session.

   Parameters:
   - session: the session atom
   - uri-template: URI template string with placeholders
   - name: programmatic name for the template
   - description: description of what the template provides
   - mime-type: MIME type of resources created from this template
   - annotations: (optional) additional metadata annotations

   Optional keyword arguments (MCP 2025-06-18):
   - :title - human-readable display name; clients SHOULD prefer it over name
     when present
   - :_meta - map of arbitrary metadata to attach to this template. Keys
     under :_meta are preserved verbatim on the wire (no kebab→camelCase
     transformation).

   Optional keyword arguments (MCP 2025-11-25):
   - :icons - vector of icon maps for display in user interfaces. Each map
     has :src (required), and optionally :mime-type, :sizes, :theme.

   Returns the session atom."
  ([session uri-template name description mime-type]
   (add-resource-template session uri-template name description mime-type nil))
  ([session uri-template name description mime-type annotations & {:keys [title _meta icons]}]
   (log/info "Adding resource template:" name "for URI template:" uri-template)
   (let [spec (-> (map-of uri-template name description mime-type annotations)
                  (?assoc :title title :_meta _meta :icons icons))]
     (swap! session update-in
            [::mcp/handlers :resource-templates]
            (fn [templates] (conj (or templates []) (h.resources/->resource-template spec)))))
   session))

(defn remove-resource-template
  "Remove a resource template from a session.
   
   Parameters:
   - session: the session atom
   - template-name: name of the template to remove
   
   Returns the session atom."
  [session template-name]
  (log/info "Removing resource template:" template-name)
  (swap! session update-in
         [::mcp/handlers :resource-templates]
         (fn [templates] (filterv #(not= template-name (:name %)) templates)))
  session)

(defn add-completion
  "Adds a completion handler to a session.

   Parameters:
   - session: the session atom
   - ref-type: type of reference (e.g., \"resource\", \"prompt\", \"tool\")
   - ref-name: name of the specific reference
   - handler: completion handler function
              (fn [exchange name value context] core/completion-resp).
              context is the optional :context map sent by the client
              (MCP 2025-06-18) carrying previously-resolved arguments under
              :arguments, or nil if the client did not include one.

   Returns the updated session atom."
  [session ref-type ref-name handler]
  (log/info "Adding completion handler for" ref-type ref-name)
  (swap! session update-in [::mcp/handlers :completions] assoc [ref-type ref-name] handler)
  session)

(defn remove-completion
  "Removes a completion handler from a session.
   
   Parameters:
   - session: the session atom
   - ref-type: type of reference
   - ref-name: name of the specific reference
   
   Returns the session atom."
  [session ref-type ref-name]
  (log/info "Removing completion handler for" ref-type ref-name)
  (swap! session update-in [::mcp/handlers :completions] dissoc [ref-type ref-name])
  session)

(defn set-completion-handler
  "Sets (or unsets if nil) a general completion handler that is called if there are no
   specific completion handlers that match.

   Parameters:
   - session: the session atom
   - handler: general completion handler function
              (fn [exchange ref-type ref-name name value context] core/completion-resp)
              or nil to unset. context is the optional :context map sent by
              the client (MCP 2025-06-18) carrying previously-resolved
              arguments under :arguments, or nil if the client did not
              include one.

   Returns the session atom."
  [session handler]
  (if handler
    (do
      (log/info "Setting default completion handler")
      (swap! session update ::mcp/handlers assoc :def-completion handler))
    (do
      (log/info "Unsetting default completion handler")
      (swap! session update ::mcp/handlers dissoc :def-completion)))
  session)

(defn set-resources-handler
  "Sets (or unsets if nil) the main resources handler that will handle resource requests.
   
   Parameters:
   - session: the session atom
   - resources-proto: resources protocol object implementing the Resources interface, or nil to unset
   
   Returns the session atom."
  [session resources-proto]
  (if resources-proto
    (do
      (log/info "Setting resources handler")
      (swap! session update ::mcp/handlers assoc :resources resources-proto))
    (do
      (log/info "Unsetting resources handler")
      (swap! session update ::mcp/handlers dissoc :resources)))
  session)

(defn make-dispatch
  "Creates a dispatch table for JSON-RPC calls with the specified middleware.
   
   Parameters:
   - middleware: (optional) middleware stack to apply
   
   This applies middleware to all endpoints in the dispatch table.
   See documentation on customizing this dispatch.
   
   Returns a configured dispatch table."
  ([] (make-dispatch []))
  ([middleware] (rpc/with-middleware mcp-functions middleware)))

(defn add-async-to-dispatch
  "Makes every function in the dispatch table run asynchronously with the provided executor.
   
   Parameters:
   - dispatch: the dispatch table to modify
   - executor: (optional) executor to use, defaults to virtual threads if available or cached thread pool
   
   Effectively wraps the dispatch table with wrap-executor middleware.
   
   Returns the modified dispatch table."
  ([dispatch]
   (add-async-to-dispatch
     dispatch
     (if (util/runtime-vthreads?)
       (-> (.getDeclaredMethod Executors "newVirtualThreadPerTaskExecutor" (make-array Class 0))
           (.invoke nil (make-array Class 0)))
       (Executors/newCachedThreadPool))))
  ([dispatch executor]
   (rpc/with-middleware dispatch [[rpc/wrap-executor executor]])))

(defn make-session
  "Make a primordial session:
   
   Mandatory parameters:
   - server-info: map with :name, :version, :instructions, and optional :logging keys
   - json-serialization: JSONSerialization instance (see json package namespaces for implementations)

   Optional params (they have defaults):
   - context is a map of anything you want available to handlers
   - dispatch is RPC dispatch table, use make-dispatch to make your own"
  ([server-info json-serialization context]
   (make-session server-info json-serialization context nil))
   ([server-info json-serialization context dispatch]
    (-> (rpc/base-session context server-info json-serialization (or dispatch (make-dispatch)))
        (atom))))

(defn set-page-size
  "Enables cursor-based pagination for list operations (tools/list, prompts/list,
   resources/list, resources/templates/list).

   When page-size is nil (the default), pagination is disabled and all items are
   returned. When set to a positive integer, list responses include a :nextCursor
   field whenever more items follow the current page.

   Parameters:
   - session:    the session atom
   - page-size:  positive integer, or nil to disable pagination

   Returns the session atom."
  [session page-size]
  (when (some? page-size)
    (when-not (and (integer? page-size) (pos? page-size))
      (throw (ex-info "page-size must be a positive integer or nil"
                      {:page-size page-size}))))
  (swap! session assoc ::mcp/page-size page-size)
  session)

(defn set-params-validator
  "Enables tool parameter schema validation against each tool's :inputSchema.

   When set, every tools/call request is validated before the tool handler is
   invoked. If the arguments do not conform to the schema, an invalid-params
   JSON-RPC error is returned immediately and the handler is never called.

   Parameters:
   - session:   the session atom
   - validator: a SchemaValidator instance (from one of the schema/* adapter
                namespaces), or nil to disable validation

   Returns the session atom.

   Example:
     (require '[org.clojars.roklenarcic.mcp-server.schema.networknt :as nnt])
     (-> session
         (server/set-page-size 50)
         (server/set-params-validator (nnt/validator)))"
  [session validator]
  (if validator
    (do (log/info "Setting tool params validator")
        (swap! session assoc ::mcp/params-validator validator))
    (do (log/info "Unsetting tool params validator")
        (swap! session dissoc ::mcp/params-validator)))
  session)

(defn start-server-on-streams
  "Starts the MCP server using the provided input and output streams.
   
   Parameters:
   - session-template: session template to use for creating the actual session
   - input-stream: input stream to read JSON-RPC messages from
   - output-stream: output stream to write JSON-RPC responses to
   - opts: options map with optional keys:
     - :client-req-timeout-ms: timeout in milliseconds for client request roundtrips
   
   The server will run until the input stream is closed or the thread is interrupted.
   Interrupting the thread will cause the server to stop gracefully."
  [session-template input-stream output-stream opts]
  (streams/run session-template input-stream output-stream opts))

(defn notify-resource-changed
  "Notifies the client that a resource has changed, if subscriptions are enabled
   and the client has subscribed to this URI.
   
   Parameters:
   - session: the session atom
   - uri: URI of the resource that changed"
  [session uri] 
  (h.resources/notify-changed session uri))

(defn notify-elicitation-complete
  "Sends notifications/elicitation/complete to the client (MCP 2025-11-25).

   Call this when an out-of-band URL-mode elicitation interaction completes on the
   server side, to inform the client that it may retry the original request.

   Parameters:
   - session: the session atom
   - elicitation-id: the elicitationId from the original elicitation/create request"
  [session elicitation-id]
  (rpc/send-notification session "notifications/elicitation/complete"
                         {:elicitationId elicitation-id}))

(defn exchange
  "Converts a session atom to a RequestExchange object."
  ([session] (exchange session nil))
  ([session req-meta] (handler/create-req-session session req-meta {})))

(defn server-info
  "Creates server information and capabilities configuration.
   
   Parameters:
   - name: server name string
   - version: server version string
   - instructions: instructions for using the server
   - logging: (optional) minimum logging level for messages sent to client
     Set to nil to disable sending log messages to client. Valid levels are:
     :debug, :info, :notice, :warning, :error, :critical, :alert, :emergency
   
   Note: Messages are always logged by the server according to your logging configuration,
   regardless of this setting. This only affects messages explicitly sent to the client."
  ([name version instructions]
   (server-info name version instructions nil))
  ([name version instructions logging]
   (map-of name version logging instructions)))

(defn server-info-ext
  "Extends a server-info map with optional MCP 2025-11-25 implementation metadata.

   Parameters:
   - info: server-info map created with the server-info function

   Optional keyword arguments (MCP 2025-11-25):
   - :title - human-readable display name for the server/client implementation
   - :description - description of the server
   - :icons - vector of icon maps, each with :src (required), and optionally
     :mime-type, :sizes (vector of strings), and :theme (\"light\" or \"dark\")
   - :website-url - URL of the server's website"
  [info & {:keys [title description icons website-url]}]
  (?assoc info :title title :description description :icons icons :websiteUrl website-url))

(defn icon
  "Creates an icon specification (MCP 2025-11-25) for use with tools, prompts,
   resources, resource templates, and server-info-ext.

   Parameters:
   - src: URI pointing to the icon resource (required). Can be an HTTPS URL
     or a data URI with base64-encoded image data.

   Optional keyword arguments:
   - :mime-type - MIME type of the icon (e.g., \"image/png\", \"image/svg+xml\")
   - :sizes - vector of size strings (e.g., [\"48x48\"], [\"any\"] for SVG)
   - :theme - theme preference (\"light\" or \"dark\")"
  [src & {:keys [mime-type sizes theme]}]
  (?assoc {:src src} :mimeType mime-type :sizes sizes :theme theme))

(defn prompt
  "Creates a Prompt spec. Required args and optional args are maps of arg_name -> arg_description.

   Handler is (fn [exchange arguments] ... )

   Optional keyword arguments (MCP 2025-06-18):
   - :title - human-readable display name; clients SHOULD prefer it over name
     when present
   - :_meta - map of arbitrary metadata to attach to this prompt. Keys
     under :_meta are preserved verbatim on the wire (no kebab→camelCase
     transformation).

   Optional keyword arguments (MCP 2025-11-25):
   - :icons - vector of icon maps for display in user interfaces. Each map
     has :src (required), and optionally :mime-type, :sizes, :theme."
  [name description required-args optional-args handler & {:keys [title _meta icons]}]
  (-> (map-of name description required-args optional-args handler)
      (?assoc :title title :_meta _meta :icons icons)))

(defn str-schema
  "Creates a string JSON schema.
   
   Parameters:
   - description: schema description
   - format: string format (e.g., 'email', 'uri', 'date-time')
   - enum: (optional) allowed values vector
   - pattern: (optional) regex pattern string
   - min-length: (optional) minimum string length
   - max-length: (optional) maximum string length"
  ([description format]
   (str-schema description format nil nil nil nil))
  ([description format enum pattern min-length max-length]
   (?assoc {} :type "string" :description description :format format :enum enum :pattern pattern
           :minLength min-length :maxLength max-length)))

(defn num-schema
  "Creates a number JSON schema.
   
   Parameters:
   - description: schema description
   - minimum: (optional) minimum value
   - maximum: (optional) maximum value
   - exclusive-minimum: (optional) exclusive minimum value
   - exclusive-maximum: (optional) exclusive maximum value
   - multiple-of: (optional) number must be multiple of this value"
  ([description] (num-schema description nil nil nil nil nil))
  ([description minimum maximum exclusive-minimum exclusive-maximum multiple-of]
   (?assoc {} :description description :minimum minimum :maximum maximum :exclusiveMinimum exclusive-minimum
           :exclusiveMaximum exclusive-maximum :multipleOf multiple-of :type "number")))

(defn int-schema
  "Creates an integer JSON schema.
   
   Parameters:
   - description: schema description
   - minimum: (optional) minimum value
   - maximum: (optional) maximum value
   - exclusive-minimum: (optional) exclusive minimum value
   - exclusive-maximum: (optional) exclusive maximum value
   - multiple-of: (optional) number must be multiple of this value"
  ([description] (int-schema description nil nil nil nil nil))
  ([description minimum maximum exclusive-minimum exclusive-maximum multiple-of]
   (?assoc {} :description description :minimum minimum :maximum maximum
           :exclusiveMinimum exclusive-minimum :exclusiveMaximum exclusive-maximum
           :multipleOf multiple-of :type "integer")))

(defn bool-schema
  [description]
  {:type "boolean" :description description})

(defn arr-schema
  "Creates an array JSON schema.
   
   Parameters:
   - description: schema description
   - item-schema: schema for array items
   - unique?: (optional) whether items must be unique
   - min-items: (optional) minimum number of items
   - max-items: (optional) maximum number of items"
  ([description item-schema] (arr-schema description item-schema nil nil nil))
  ([description item-schema unique? min-items max-items]
   (?assoc {}
           :items item-schema :uniqueItems unique? :minItems min-items :maxItems max-items
           :type "array" :description description)))

(defn obj-schema
  "Creates an object JSON schema.
   
   Parameters:
   - description: schema description
   - properties: map of property names to schemas
   - required: vector of required property names
   - additionalProperties: (optional) whether additional properties are allowed
   - patternProperties: (optional) map of regex patterns to schemas"
  ([description properties required]
   (obj-schema description properties required nil nil))
  ([description properties required additionalProperties patternProperties]
   (?assoc {}
           :description description :properties properties :required required :type "object"
           :additionalProperties additionalProperties :patternProperties patternProperties)))

(defn tool
  "Creates a tool specification for registration with the server.

   A tool is a function that can be called by the client with structured parameters.

   Parameters:
   - name: tool name (string)
   - description: tool description (string)
   - input-schema: object JSON schema describing the tool's input parameters
   - handler: tool handler function (fn [exchange params] ...)

    Optional keyword arguments (MCP 2025-06-18):
   - :title - human-readable display name; clients SHOULD prefer it over name
     when present
   - :output-schema - object JSON Schema describing the structure of
     :structuredContent the tool returns. When provided, handlers should
     return values built with core/tool-result so the wire response carries
     :structuredContent in addition to :content.
   - :_meta - map of arbitrary metadata to attach to this tool. Keys
     under :_meta are preserved verbatim on the wire (no kebab→camelCase
     transformation).

   Optional keyword arguments (MCP 2025-11-25):
   - :icons - vector of icon maps for display in user interfaces. Each map
     has :src (required), and optionally :mime-type, :sizes, :theme.

   JSON Schema supports the following types and constraints:

   Basic Types:
   - string: Text values
   - number: Numeric values (integers and floats)
   - integer: Whole numbers only
   - boolean: true/false values
   - array: Lists of items
   - object: Complex structured data
   - null: Null values

   Type Combinations:
   - anyOf: Value must match one of several schemas
   - oneOf: Value must match exactly one of several schemas
   - allOf: Value must match all the specified schemas

   String Constraints:
   - enum: Restrict to specific allowed values
   - pattern: Regular expression validation
   - minLength/maxLength: String length constraints
   - format: Standard formats like \"email\", \"uri\", \"date-time\", etc.

   Numeric Constraints:
   - minimum/maximum: Value bounds
   - exclusiveMinimum/exclusiveMaximum: Exclusive bounds
   - multipleOf: Must be multiple of specified number

   Array Constraints:
   - items: Schema for array elements
   - minItems/maxItems: Array length constraints
   - uniqueItems: Whether items must be unique

   Object Constraints:
   - properties: Define object properties
   - required: Specify required properties
   - additionalProperties: Control extra properties
   - patternProperties: Properties matching patterns"
  [name description input-schema handler & {:keys [title output-schema _meta icons]}]
  (?assoc {:name name :description description :inputSchema input-schema :handler handler}
          :title title
          :outputSchema output-schema
          :_meta _meta
          :icons icons))