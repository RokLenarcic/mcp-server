(ns org.clojars.roklenarcic.mcp-server.json-rpc
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [?assoc]])
  (:import (clojure.lang IAtom)
           (java.util.concurrent CancellationException CompletableFuture ConcurrentHashMap ConcurrentLinkedQueue TimeoutException)
           (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(def ^ConcurrentLinkedQueue client-req-queue
  "Queue for tracking client request timestamps and IDs for timeout management."
  (ConcurrentLinkedQueue.))

(def client-req-cnt 
  "Atomic counter for generating unique request IDs."
  (atom 0))

(def ^ConcurrentHashMap client-req-pending
  "Map of pending client requests, keyed by [request-id session-key], where
  session-key is the HTTP session id (string) for Streamable HTTP transports
  or the session atom identity for stream/stdio transports.

  Composite keys prevent a response delivered on session B from completing a
  pending request that originated on session A."
  (ConcurrentHashMap.))

(def last-cleanup (atom 0))

(defn client-req-key
  "Builds the composite key used by `client-req-pending` for a request.

  Uses the HTTP session id (`::mcp/id`) when present, otherwise falls back to
  the session atom identity so non-HTTP transports (stdio, streams) still get
  a stable per-session key."
  [rpc-session id]
  [(long id) (or (::mcp/id @rpc-session) rpc-session)])

(defn cleanup-requests
  "Removes expired client requests from the queue and completes them with timeout errors.

  This function can be called by multiple threads and it still works correctly

   Parameters:
   - expire-ms: timeout in milliseconds after which requests are considered expired"
  [expire-ms]
  (let [t (System/currentTimeMillis)
        iter (.iterator ^ConcurrentLinkedQueue client-req-queue)
        expired-ts (- t expire-ms)
        expired-key (fn [[created-ts id session-key]]
                      (when (< created-ts expired-ts) [id session-key]))]
    ;; throttle to once per 500ms
    (when (= t (swap! last-cleanup #(if (= (quot % 500) (quot t 500)) % t)))
      (log/trace "Cleaning up client requests older than" expire-ms "ms")
      (loop [expired-count 0]
        (if-let [key (and (.hasNext iter) (expired-key (.next iter)))]
          (do
            (.remove iter)
            (when-let [fut (.remove client-req-pending key)]
              (log/debug "Timing out client request with key:" key)
              (.completeExceptionally ^CompletableFuture fut (TimeoutException. "Client Request Timed Out")))
            (recur (inc expired-count)))
          (when (> expired-count 0)
            (log/info "Cleaned up" expired-count "expired client requests")))))))

(defprotocol JSONSerialization
  "Protocol for JSON serialization implementations."
  (json-serialize [this o] "Serializes an object to JSON string. Returns string representation.")
  (json-deserialize [this s] "Deserializes a JSON string to Clojure data. Returns Exception instance on parse error."))

(defn base-session
  "Creates a base session with core configuration.
   
   Parameters:
   - base-context: map of additional context data
   - server-info: server information map
   - serde: JSON serialization implementation
   - dispatch-table: function dispatch table for handling requests
   
   Returns a session map with all core MCP configuration."
  [base-context server-info serde dispatch-table]
  (merge #::mcp {:server-info server-info
                 :serde serde
                 :dispatch-table dispatch-table
                 :handlers {}
                 :in-flight {}
                 :logging-level (:logging server-info)}
         base-context))

(defn update-inflight [context f id & args]
  (when id
    (if (instance? IAtom context)
      (apply swap! context update ::mcp/in-flight (fnil f {}) id args)
      (apply update context ::mcp/in-flight (fnil f {}) id args)))
  nil)

(defn make-error-response
  "Creates a JSON-RPC error response map.
   
   Parameters:
   - error-code: numeric error code
   - message: error message string
   - data: (optional) additional error data
   - id: request ID
   
   Returns a JSON-RPC error response map."
  ([error-code message id] (make-error-response error-code message nil id))
  ([error-code message data id]
   (?assoc {:jsonrpc "2.0"
            :error (?assoc {:code error-code :message message} :data data)}
           :id id)))

(defn make-response
  "Creates a JSON-RPC success response map.
   
   Parameters:
   - result: the result data (or JSONRPCError for error responses)
   - id: request ID
   
   Returns a JSON-RPC response map."
  [result id]
  (if (instance? JSONRPCError result)
    (make-error-response (:code result) (:message result) (:data result) id)
    {:jsonrpc "2.0" :result result :id id}))

(defn invalid-request
  "Creates an invalid request error response.
   
   Parameters:
   - data: (optional) error data
   - id: request ID
   
   Returns an invalid request error response."
  ([id] (invalid-request nil id))
  ([data id] (make-error-response p/INVALID_REQUEST "Invalid Request" data id)))

(defn wrap-error
  "Wraps a handler with error handling middleware that catches exceptions
   and converts them to JSON-RPC error responses.
   
   Parameters:
   - handler: the handler function to wrap
   - logging-level: logging level for caught exceptions
   
   Returns a wrapped handler function."
  [handler logging-level]
  (fn error-middleware [context req-meta params]
    (try
      (let [resp (handler context req-meta params)]
        (if (instance? CompletableFuture resp)
          (.exceptionally ^CompletableFuture resp
                          (fn [e]
                            (log/logp logging-level e "Error in handler")
                            (c/internal-error nil (ex-message e))))
          resp))
      (catch Exception e
        (log/logp logging-level e "Error in handler")
        (c/internal-error nil (ex-message e))))))

(defn wrap-executor
  "Wraps a handler to run asynchronously using the provided executor.
   
   Parameters:
   - handler: the handler function to wrap
   - executor: executor service to use for async execution
   
   Returns a wrapped handler that returns a CompletableFuture."
  [handler executor]
  (fn executor-middleware [context req-meta params]
    (-> #(handler context req-meta params)
        (CompletableFuture/supplyAsync executor)
        (.thenCompose (fn [ret]
                        (if (instance? CompletableFuture ret) 
                          ret 
                          (CompletableFuture/completedFuture ret)))))))

(defn method-not-found-handler
  "Creates a handler that always reports that a method hasn't been found.
   
   Parameters:
   - method: the method name that was not found
   
   Returns a handler function that returns a method not found error."
  [method]
  (fn [_ _ _] (c/->JSONRPCError p/METHOD_NOT_FOUND (format "Method '%s' not found." method) nil)))

(defn handle-client-response
  "Handles responses from the client to our requests.

   Parameters:
   - context: the session atom — used to derive the composite pending-key
   - _req-meta: unused
   - params: response parameters containing :error, :result, and :id

   The pending future is looked up by `[id session-key]`. The following cases
   are tolerated and ignored (returning nil, which the HTTP transport maps to
   202 No Content):
   - missing or non-numeric `:id` (malformed client response)
   - `:id` that does not correspond to any pending server-initiated request
   - `:id` that corresponds to a request issued on a different session

   In each of those cases a debug-level message is logged so operators can
   diagnose mis-routed or stale client responses without surfacing an error
   to the misbehaving client.

   Returns nil (this is a notification-shaped handler)."
  [context _ {:keys [error result id] :as params}]
  (if
    (or (nil? params) (nil? id))
    (log/debug "Ignoring client response with missing id")
    (if-let [^CompletableFuture cb (.remove client-req-pending (client-req-key context id))]
      (do
        (log/debug "Handling client response for id:" id "error:" (some? error))
        (if-let [{:keys [code message data]} error]
          (do (log/debug "Client responded with error - code:" code "message:" message)
              (.completeExceptionally cb (ex-info message (merge data {:code code :type :jsonrpc-client-error}))))
          (do (log/debug "Client responded with success result")
              (.complete cb result)))
        nil)
      (log/debug "Ignoring client response for unknown or wrong-session id:" id
                 "(no pending request for this session)"))))

(defn obj->jrpc-resp
  [context resp id]
  (when-let [^CompletableFuture fut (get (::mcp/in-flight (if (instance? IAtom context) @context context)) id)]
    (update-inflight context dissoc id)
    (if (.isDone fut)
      (log/debugf "Request %s cancelled, response ignored" id)
      (if (and (map? resp) (= "2.0" (:jsonrpc resp)))
        (assoc resp :id id)
        (make-response resp id)))))

(defn handle-parsed
  "Handles a parsed JSON-RPC message.
   
   Parameters:
   - parsed: parsed message object
   - dispatch-table: function dispatch table
   - context: handler context, typically the session atom
   - req-meta: request-specific metadata passed to dispatch handlers
   
   Returns a JSON-RPC response object or CompletableFuture."
  [parsed dispatch-table context req-meta]
  (let [{:keys [id error method params item-type]} parsed
        handle-ex (fn [e]
                    (log/error e "Error handling request ID" id)
                    (make-error-response p/INTERNAL_ERROR (ex-message e) id))]
    (log/debug "Handling parsed message - method:" method "type:" item-type "id:" id)
    (case item-type
      :error {:jsonrpc "2.0" :error error :id id}
      (try
        (update-inflight context assoc id (CompletableFuture.))
        (let [handler (get dispatch-table method (method-not-found-handler method))
              result (handler context (?assoc req-meta ::mcp/request-id id) params)]
          (if (instance? CompletableFuture result)
            (-> ^CompletableFuture result
                (.exceptionally handle-ex)
                (.thenApply (fn [resp] (obj->jrpc-resp context resp id))))
            (obj->jrpc-resp context result id)))
        (catch Exception e (obj->jrpc-resp context (handle-ex e) id))))))

(defn parse-string [msg serde] (-> serde (json-deserialize msg) p/object->requests))

(defn apply-middleware
  "Applies middleware to a handler. Middleware is Reitit style vectors."
  [handler middleware-stack]
  (reduce (fn [h middleware]
            (if (vector? middleware)
              (apply (first middleware) h (rest middleware))
              (middleware h)))
          handler
          (reverse middleware-stack)))

(defn with-middleware
  "Applies same middleware to all handlers in a dispatch table. Middleware is Reitit style vectors."
  [dispatch-table middleware-stack]
  (update-vals dispatch-table #(apply-middleware % middleware-stack)))

(defn send-notification
  "Sends notification to the client."
  [rpc-session method params]
  (let [{::mcp/keys [send-to-client serde]} @rpc-session]
    (log/debug "Sending notification:" method "with params:" (some? params))
    (send-to-client (json-serialize serde (?assoc {:jsonrpc "2.0" :method method} :params params)))
    nil))

(defn send-request
  "Sends a request to the client and returns a CompletableFuture for the response.
   
   Parameters:
   - rpc-session: the session atom
   - method: request method name
   - params: (optional) request parameters
   - cancel-handler: handler function when cancel method is called on returned future
   
   Returns a CompletableFuture that will complete with the client's response."
  [rpc-session method params cancel-handler]
  (let [{::mcp/keys [send-to-client serde]} @rpc-session
        id (swap! client-req-cnt inc)
        fut (proxy [CompletableFuture] []
              (cancel [interrupt-if-running]
                (when interrupt-if-running (cancel-handler id))
                (.completeExceptionally ^CompletableFuture this (CancellationException.))))
        req (?assoc {:jsonrpc "2.0" :id id :method method} :params params)
        [_ session-key :as pending-key] (client-req-key rpc-session id)]
    (log/debug "Sending request:" method "with id:" id "params:" (some? params))
    (send-to-client (json-serialize serde req))
    (.offer client-req-queue [(System/currentTimeMillis) id session-key])
    (.put client-req-pending pending-key fut)
    (log/debug "Request" id "queued, pending responses:" (.size client-req-pending))
    fut))
