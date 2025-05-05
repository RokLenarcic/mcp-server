(ns org.clojars.roklenarcic.mcp-server.json-rpc
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [?assoc]])
  (:import (java.util.concurrent CompletableFuture ConcurrentHashMap ConcurrentLinkedQueue TimeoutException)
           (java.util.function BiFunction Function)
           (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(def ^ConcurrentLinkedQueue client-req-queue (ConcurrentLinkedQueue.))
(def client-req-cnt (atom 0))
(def ^ConcurrentHashMap client-req-pending (ConcurrentHashMap.))

(defprotocol JSONSerialization
  (json-serialize [this o] "Serializes an object")
  (json-deserialize [this s] "Deserializes string, returns Exception instance on bad parse"))

(defn base-session [base-context server-info serde dispatch-table]
  (merge #::mcp {:server-info server-info
                 :serde serde
                 :dispatch-table dispatch-table
                 :handlers {}
                 :logging-level (:logging server-info)}
         base-context))

(defn make-error-response
  "Create a JSON-RPC error response map"
  ([error-code message id] (make-error-response error-code message nil id))
  ([error-code message data id]
   (?assoc {:jsonrpc "2.0"
            :error (?assoc {:code error-code :message message} :data data)}
           :id id)))

(defn make-response
  "Create a JSON-RPC success response map"
  [result id]
  (if (instance? JSONRPCError result)
    (make-error-response (:code result) (:message result) (:data result) id)
    {:jsonrpc "2.0" :result result :id id}))

(defn invalid-request
  "Create an invalid request error response"
  ([id] (invalid-request nil id))
  ([data id]
   (make-error-response p/INVALID_REQUEST "Invalid Request" data id)))

;; Predefined error response constructors

(defn wrap-error
  "Wraps handler into error handling middleware that emits any exceptions
  as Internal Error JSONRPC messages and logs error do debug"
  [handler logging-level]
  (fn error-middleware [context params]
    (try
      (let [resp (handler context params)]
        (if (instance? CompletableFuture resp)
          (.exceptionally ^CompletableFuture resp
                          (fn [e]
                            (log/logp logging-level e)
                            (c/internal-error nil (ex-message e))))
          resp))
      (catch Exception e
        (log/logp logging-level e)
        (c/internal-error nil (ex-message e))))))

(defn wrap-executor
  "Wraps handler with code that runs the handler using provided executor."
  [handler executor]
  (fn executor-middleware [context params]
    (-> #(handler context params)
        (CompletableFuture/supplyAsync executor)
        (.thenCompose (fn [ret]
                        (if (instance? CompletableFuture ret) ret (CompletableFuture/completedFuture ret)))))))

(defn combine-futures
  "If result vector contains any futures, create a future that completes with all the other futures
  resolved."
  [responses]
  (let [{normal false futs true} (group-by #(instance? CompletableFuture %) responses)]
    (if (seq futs)
      (-> #(.thenCombine ^CompletableFuture %1 ^CompletableFuture %2 ^BiFunction conj)
          ^CompletableFuture (reduce (CompletableFuture/completedFuture normal) futs)
          (.thenApply ^Function not-empty))
      (not-empty normal))))

(defn method-not-found-handler
  [method]
  (fn [_ _]
    (log/debug (format "Method '%s' not found." method))
    (c/->JSONRPCError p/METHOD_NOT_FOUND (format "Method '%s' not found." method) nil)))

(defn cleanup-requests
  "Remove expired requests to client from Client Request queue and Client Request map"
  [expire-ms]
  (let [iter (.iterator ^ConcurrentLinkedQueue client-req-queue)
        cut-off (- (System/currentTimeMillis) expire-ms)
        expired-id (fn [[t id]] (when (< t cut-off) id))]
    (loop []
      (when-let [id (and (.hasNext iter)
                         (expired-id (.next iter)))]
        (.remove iter)
        (when-let [fut (.remove client-req-pending id)]
          (.completeExceptionally ^CompletableFuture fut
                                  (TimeoutException. "Client Request Timed Out")))
        (recur)))))

(defn handle-client-response
  [_ {:keys [error result id] :as params}]
  (when-let [^CompletableFuture cb (and params id (.remove client-req-pending id))]
    (if-let [{:keys [code message data]} error]
      (.completeExceptionally cb (ex-info message (merge data {:code code :type :jsonrpc-client-error})))
      (.complete cb result))
    nil))

(defn handle-parsed
  "Handle-msg should be a fn that handles a single message."
  [parsed dispatch-table context]
  (let [{:keys [id error method params item-type]} parsed]
    (log/debug "Handling message" parsed)
    (case item-type
      :error {:jsonrpc "2.0" :error error :id id}
      (let [result ((get dispatch-table method (method-not-found-handler method)) context params)
            _ (log/debug "Result" result)
            obj->jrpc-resp #(if (and (map? %) (= "2.0" (:jsonrpc %)))
                              (assoc % :id id)
                              (make-response % id))]
        (when id
          (if (instance? CompletableFuture result)
            (-> ^CompletableFuture result
                (.exceptionally #(make-error-response p/INTERNAL_ERROR (ex-message %) nil))
                (.thenApply obj->jrpc-resp))
            (obj->jrpc-resp result)))))))

(defn parse-string
  "Handles serde and batching logic in relation to parsing."
  [msg serde]
  (-> serde (json-deserialize msg) p/parse-string))

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
    (send-to-client (json-serialize serde (?assoc {:jsonrpc "2.0" :method method} :params params)))
    nil))

(defn send-request
  "Sends request to the client, returns CompletableFuture."
  [rpc-session method params]
  (let [{::mcp/keys [send-to-client serde]} @rpc-session
        fut (CompletableFuture.)
        id (swap! client-req-cnt inc)
        req (?assoc {:jsonrpc "2.0" :id id :method method} :params params)]
    (send-to-client (json-serialize serde req))
    (.offer client-req-queue [(System/currentTimeMillis) id])
    (.put client-req-pending id fut)
    fut))
