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
            [org.clojars.roklenarcic.mcp-server.util :as util :refer [map-of ?assoc camelcase-keys]])
  (:import (java.util.concurrent Executors)))

(def mcp-functions
  "Core MCP function dispatch table mapping method names to handlers."
  (-> {"ping" (constantly {})  ; Basic ping handler for connectivity testing
       :client-resp rpc/handle-client-response  ; Handle responses from client
       "logging/setLevel" h.logging/logging-set-level  ; Set logging level
       "notifications/roots/list_changed" handler/handle-changed-root  ; Handle root changes
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
  (swap! session update-in [::mcp/handlers :tools] assoc (:name tool-spec) (camelcase-keys tool-spec))
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
   - name: human-readable name for the template
   - description: description of what the template provides
   - mime-type: MIME type of resources created from this template
   - annotations: (optional) additional metadata annotations
   
   Returns the session atom."
  ([session uri-template name description mime-type]
   (log/info "Adding resource template:" name "for URI template:" uri-template)
   (swap! session update-in
          [::mcp/handlers :resource-templates]
          (fn [templates] (conj (or templates []) (h.resources/->resource-template (map-of uri-template name description mime-type)))))
   session)
  ([session uri-template name description mime-type annotations]
   (log/info "Adding resource template with annotations:" name "for URI template:" uri-template)
   (swap! session update-in
          [::mcp/handlers :resource-templates]
          (fn [templates] (conj (or templates []) (h.resources/->resource-template (map-of uri-template name description mime-type annotations)))))
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
   - handler: completion handler function (fn [exchange name value] core/completion-resp)
   
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
   - handler: general completion handler function (fn [exchange ref-type ref-name name value] core/completion-resp)
              or nil to unset
   
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
  (streams/run (streams/create-session session-template output-stream) input-stream opts))

(defn notify-resource-changed
  "Notifies the client that a resource has changed, if subscriptions are enabled
   and the client has subscribed to this URI.
   
   Parameters:
   - session: the session atom
   - uri: URI of the resource that changed"
  [session uri] 
  (h.resources/notify-changed session uri))

(defn exchange
  "Converts a session atom to a RequestExchange object for use in handlers."
  ([session] (exchange session nil))
  ([session progress-token] (handler/create-req-session session progress-token)))

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

(defn prompt
  "Creates a Prompt spec. Required args and optional args are maps of arg_name -> arg_description

  Handler is (fn [exchange arguments] ... )"
  [name description required-args optional-args handler]
  (map-of name description required-args optional-args handler))

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
   - input-schema: JSON schema object describing the tool's input parameters
   - handler: tool handler function (fn [exchange params] ...)
   
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
   - allOf: Value must match all of the specified schemas
   
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
  [name description input-schema handler]
  (map-of name description input-schema handler))