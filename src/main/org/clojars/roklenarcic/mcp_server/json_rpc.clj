(ns org.clojars.roklenarcic.mcp-server.json-rpc
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [?assoc]])
  (:import (java.util.concurrent CompletableFuture ConcurrentHashMap ConcurrentLinkedQueue TimeoutException)
           (java.util.function BiFunction Function)
           (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(def ^ConcurrentLinkedQueue client-req-queue
  "Queue for tracking client request timestamps and IDs for timeout management."
  (ConcurrentLinkedQueue.))

(def client-req-cnt 
  "Atomic counter for generating unique request IDs."
  (atom 0))

(def ^ConcurrentHashMap client-req-pending
  "Map of pending client requests by ID to their CompletableFuture objects."
  (ConcurrentHashMap.))

(def last-cleanup (atom 0))

(defn cleanup-requests
  "Removes expired client requests from the queue and completes them with timeout errors.

  This function can be called by multiple threads and it still works correctly

   Parameters:
   - expire-ms: timeout in milliseconds after which requests are considered expired"
  [expire-ms]
  (let [t (System/currentTimeMillis)
        iter (.iterator ^ConcurrentLinkedQueue client-req-queue)
        expired-ts (- t expire-ms)
        expired-id (fn [[created-ts id]] (when (< created-ts expired-ts) id))]
    ;; throttle to once per 500ms
    (when (= t (swap! last-cleanup #(if (= (quot % 500) (quot t 500)) % t)))
      (log/debug "Cleaning up client requests older than" expire-ms "ms")
      (loop [expired-count 0]
        (if-let [id (and (.hasNext iter) (expired-id (.next iter)))]
          (do
            (.remove iter)
            (when-let [fut (.remove client-req-pending id)]
              (log/debug "Timing out client request with id:" id)
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
                 :logging-level (:logging server-info)}
         base-context))

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
  (fn error-middleware [context params]
    (try
      (let [resp (handler context params)]
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
  (fn executor-middleware [context params]
    (-> #(handler context params)
        (CompletableFuture/supplyAsync executor)
        (.thenCompose (fn [ret]
                        (if (instance? CompletableFuture ret) 
                          ret 
                          (CompletableFuture/completedFuture ret)))))))

(defn combine-futures
  "Combines multiple responses, some of which may be CompletableFutures.
   
   Parameters:
   - responses: vector of responses (mix of regular values and CompletableFutures)
   
   Returns either a vector of resolved values or a CompletableFuture that resolves
   to a vector when all futures complete."
  [responses]
  (let [{normal false futs true} (group-by #(instance? CompletableFuture %) responses)]
    (if (seq futs)
      (-> #(.thenCombine ^CompletableFuture %1 ^CompletableFuture %2 ^BiFunction conj)
          ^CompletableFuture (reduce (CompletableFuture/completedFuture normal) futs)
          (.thenApply ^Function not-empty))
      (not-empty normal))))

(defn method-not-found-handler
  "Creates a handler that always reports that a method hasn't been found.
   
   Parameters:
   - method: the method name that was not found
   
   Returns a handler function that returns a method not found error."
  [method]
  (fn [_ _] (c/->JSONRPCError p/METHOD_NOT_FOUND (format "Method '%s' not found." method) nil)))

(defn handle-client-response
  "Handles responses from the client to our requests.
   
   Parameters:
   - _: unused context parameter
   - params: response parameters containing :error, :result, and :id
   
   Returns nil (this is a notification handler)."
  [_ {:keys [error result id] :as params}]
  (when-let [^CompletableFuture cb (and params id (.remove client-req-pending id))]
    (log/debug "Handling client response for id:" id "error:" (some? error))
    (if-let [{:keys [code message data]} error]
      (do
        (log/debug "Client responded with error - code:" code "message:" message)
        (.completeExceptionally cb (ex-info message (merge data {:code code :type :jsonrpc-client-error}))))
      (do
        (log/debug "Client responded with success result")
        (.complete cb result)))
    nil))

(defn handle-parsed
  "Handles a parsed JSON-RPC message.
   
   Parameters:
   - parsed: parsed message object
   - dispatch-table: function dispatch table
   - context: request context
   
   Returns a JSON-RPC response object or CompletableFuture."
  [parsed dispatch-table context]
  (let [{:keys [id error method params item-type]} parsed]
    (log/debug "Handling parsed message - method:" method "type:" item-type "id:" id)
    (case item-type
      :error {:jsonrpc "2.0" :error error :id id}
      (let [handler (get dispatch-table method (method-not-found-handler method))
            result (handler context params)
            obj->jrpc-resp #(if (and (map? %) (= "2.0" (:jsonrpc %)))
                              (assoc % :id id)
                              (make-response % id))]
        (when id
          (if (instance? CompletableFuture result)
            (-> ^CompletableFuture result
                (.thenApply obj->jrpc-resp)
                (.exceptionally #(do
                                   (log/error % "Error handling request ID" id)
                                   (make-error-response p/INTERNAL_ERROR (ex-message %) id))))
            (obj->jrpc-resp result)))))))

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
   
   Returns a CompletableFuture that will complete with the client's response."
  [rpc-session method params]
  (let [{::mcp/keys [send-to-client serde]} @rpc-session
        fut (CompletableFuture.)
        id (swap! client-req-cnt inc)
        req (?assoc {:jsonrpc "2.0" :id id :method method} :params params)]
    (log/debug "Sending request:" method "with id:" id "params:" (some? params))
    (send-to-client (json-serialize serde req))
    (.offer client-req-queue [(System/currentTimeMillis) id])
    (.put client-req-pending id fut)
    (log/debug "Request" id "queued, pending responses:" (.size client-req-pending))
    fut))
