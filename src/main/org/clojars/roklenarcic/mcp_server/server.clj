(ns org.clojars.roklenarcic.mcp-server.server
  (:require [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
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

(def mcp-functions (-> {"ping" (constantly {})
                        :client-resp rpc/handle-client-response
                        "logging/setLevel" h.logging/logging-set-level
                        "notifications/roots/list_changed" handler/handle-changed-root
                        "completion/complete" h.completions/handler}
                       h.init/add-init-handlers
                       h.resources/add-resources-handlers
                       h.prompts/add-prompt-handlers
                       h.tools/add-tool-handlers))

(def ^{:arglists '([handler logging-level])} wrap-error
  "Wraps handler with code that emits INTERNAL_ERROR JSON-RPC responses on exceptions"
  rpc/wrap-error)

;; wrap-error is outermost middleware
(def std-middleware [[wrap-error :info]])

(defn add-tool
  "Adds a tool to a session, and notifies client see #'tool"
  [session tool-spec]
  (swap! session update-in [::mcp/handlers :tools] assoc (:name tool-spec) (camelcase-keys tool-spec))
  session)

(defn remove-tool
  "Removes a tool from a session and notifies client"
  [session tool-name]
  (swap! session update-in [::mcp/handlers :tools] dissoc tool-name)
  session)

(defn add-prompt
  "Adds a prompt to a session and notifies client, see #'prompt"
  [session prompt-spec]
  (swap! session update-in [::mcp/handlers :prompts] assoc (:name prompt-spec) (h.prompts/->prompt prompt-spec))
  session)

(defn remove-prompt
  "Removes a prompt from a session and notifies client."
  [session prompt-name]
  (swap! session update-in [::mcp/handlers :prompts] assoc prompt-name)
  session)

(defn set-roots-changed-callback
  "Sets (or unsets if nil) a callback that is (fn [exchange] ...), called whenever client's roots change"
  [session callback]
  (if callback
    (swap! session update ::mcp/handlers assoc :roots-changed-callback callback)
    (swap! session update ::mcp/handlers dissoc :roots-changed-callback))
  session)

(defn add-resource-template
  "Add a resource template to a session."
  ([session uri-template name description mime-type]
   (swap! session update-in
          [::mcp/handlers :resource-templates]
          (fn [templates] (conj (or templates []) (h.resources/->resource-template (map-of uri-template name description mime-type)))))
   session)
  ([session uri-template name description mime-type annotations]
   (swap! session update-in
          [::mcp/handlers :resource-templates]
          (fn [templates] (conj (or templates []) (h.resources/->resource-template (map-of uri-template name description mime-type annotations)))))
   session))

(defn remove-resource-template
  "Remove a resource template from a session"
  [session template-name]
  (swap! session update-in
         [::mcp/handlers :resource-templates]
         (fn [templates] (filterv #(not= template-name (:name %)) templates)))
  session)

(defn add-completion
  "Adds a completion handler to a session, a (fn [exchange name value] core/completion-resp)"
  [session ref-type ref-name handler]
  (swap! session update-in [::mcp/handlers :completions] assoc [ref-type ref-name] handler)
  session)

(defn remove-completion
  "Removes a completion handler from a session."
  [session ref-type ref-name]
  (swap! session update-in [::mcp/handlers :completions] dissoc [ref-type ref-name])
  session)

(defn set-completion-handler
  "Sets (or unsets if nil) a general completion handler, that is called if there are no
  specific completion handler that matches.

  Handler is (fn [exchange ref-type ref-name name value] core/completion-resp)"
  [session handler]
  (if handler
    (swap! session update ::mcp/handlers assoc :def-completion handler)
    (swap! session update ::mcp/handlers dissoc :def-completion))
  session)

(defn set-resources-handler
  "Add resources/Resources object that will handle requests for resources."
  [session resources-proto]
  (if resources-proto
    (swap! session update ::mcp/handlers assoc :resources resources-proto)
    (swap! session update ::mcp/handlers dissoc :resources))
  session)

(defn make-dispatch
  "Makes a dispatch for JSONRPC calls with the call table and middleware.

  This applies middleware (defaults to std-middleware) to all endpoints.

  See documentation on customizing this dispatch."
  ([] (make-dispatch std-middleware))
  ([middleware] (rpc/with-middleware mcp-functions middleware)))

(defn add-async-to-dispatch
  "Makes every function in dispatch table run with executor provided (or default executor).

  Effectively wraps dispatch with wrap-executor"
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

  Mandatory params:
  - server-info {:name ... :version ... :instructions ... :logging ...}
  - json-serialization is JSONSerialization instance, see json package namespaces for some implementations

  Optional params (they have defaults):
  - context is a map of anything you want available to handlers
  - dispatch is RPC dispatch table, use make-dispatch to make your own"
  ([server-info json-serialization context]
   (make-session server-info json-serialization context nil))
  ([server-info json-serialization context dispatch]
   (-> (rpc/base-session context server-info json-serialization (or dispatch (make-dispatch)))
       (atom))))

(defn start-server-on-streams
  "Starts running the server using input and output stream provided. Interrupting
   the thread will cause the server to stop. Closing the input stream also stops the process.

   Opts:
   - :client-req-timeout-ms: timeout for request to client roundtrip"
  [session-template input-stream output-stream opts]
  (streams/run (streams/create-session session-template output-stream) input-stream opts))

(defn notify-resource-changed
  "Notifies a client that a resource changed if subscriptions are enabled and client has subscribed for this URI"
  [session uri] (h.resources/notify-changed session uri))

(defn exchange
  "Convert session to a RequestExchange"
  [session]
  (handler/create-req-session session))

(defn server-info
  "Server information and capabilities information.

  - logging: set minimum logging level for setting messages to client,
  messages that are explicitly sent by invoking exchange's log-msg. If
  nil then messages are not sent to client. Note that these messages are
  logged by the server (subject to your logging configuration on server).
  "
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
  ([description format]
   (str-schema description format nil nil nil nil))
  ([description format enum pattern min-length max-length]
   (?assoc {} :type "string" :description description :format format :enum enum :pattern pattern
           :minLength min-length :maxLength max-length)))

(defn num-schema
  ([description] (num-schema description nil nil nil nil nil))
  ([description minimum maximum exclusive-minimum exclusive-maximum multiple-of]
   (?assoc {} :description description :minimum minimum :maximum maximum :exclusiveMinimum exclusive-minimum
           :exclusiveMaximum exclusive-maximum :multipleOf multiple-of :type "number")))

(defn int-schema
  ([description] (int-schema description nil nil nil nil nil))
  ([description minimum maximum exclusive-minimum exclusive-maximum multiple-of]
   (?assoc {} :description description :minimum minimum :maximum maximum
           :exclusiveMinimum exclusive-minimum :exclusiveMaximum exclusive-maximum
           :multipleOf multiple-of :type "integer")))

(defn bool-schema
  [description]
  {:type "boolean" :description description})

(defn arr-schema
  ([description item-schema] (arr-schema description item-schema))
  ([description item-schema unique? min-items max-items]
   (?assoc {}
           :items item-schema :uniqueItems unique? :minItems min-items :maxItems max-items
           :type "array" :description description)))

(defn obj-schema
  ([description properties required]
   (obj-schema description properties required nil nil))
  ([description properties required additionalProperties patternProperties]
   (?assoc {}
           :description description :properties properties :required required :type "object"
           :additionalProperties additionalProperties :patternProperties patternProperties)))

(defn tool
  "Creates a Tool spec. required-args is a vector of argument names, input-schema is an object schema.

  Handler is (fn [exchange params)).

  Basic Types

  string - Text values
  number - Numeric values (integers and floats)
  integer - Whole numbers only
  boolean - true/false values
  array - Lists of items
  object - Complex structured data
  null - Null values

  Type Combinations

  anyOf - Value must match one of several schemas
  oneOf - Value must match exactly one of several schemas
  allOf - Value must match all of the specified schemas

  String Constraints

  enum - Restrict to specific allowed values
  pattern - Regular expression validation
  minLength/maxLength - String length constraints
  format - Standard formats like \"email\", \"uri\", \"date-time\", etc.

  Numeric Constraints

  minimum/maximum - Value bounds
  exclusiveMinimum/exclusiveMaximum - Exclusive bounds
  multipleOf - Must be multiple of specified number

  Array Constraints

  items - Schema for array elements
  minItems/maxItems - Array length constraints
  uniqueItems - Whether items must be unique

  Object Constraints

  properties - Define object properties
  required - Specify required properties
  additionalProperties - Control extra properties
  patternProperties - Properties matching patterns"
  [name description input-schema handler]
  (map-of name description input-schema handler))