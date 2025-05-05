(ns org.clojars.roklenarcic.mcp-server.server.http
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.server.sse :as sse]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply pcatch]])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent CompletableFuture ConcurrentHashMap ConcurrentLinkedQueue)))

(defn- new-session-id []
  (let [buffer (byte-array 32)]
    (.nextBytes (SecureRandom.) buffer)
    (.encodeToString (Base64/getUrlEncoder) buffer)))

(defprotocol Sessions
  "A store for sessions."
  (get-session [this session-id] "Retrieves a session")
  (delete-session [this session-id] "Removes a session")
  (create-session [this] "Creates a new session, returning it (and storing it as well)")
  (all-sessions [this] "Returns all sessions"))

(defrecord MemorySessionStore
  [session-creation-fn ^ConcurrentHashMap storage]
  Sessions
  (get-session [this session-id] (.get storage session-id))
  (delete-session [this session-id] (.remove storage session-id))
  (create-session [this]
    (let [sess (session-creation-fn)]
      (swap! sess assoc ::mcp/store this ::mcp/id (new-session-id))
      (.put storage (::mcp/id @sess) sess)
      sess))
  (all-sessions [this] (iterator-seq (.iterator (.values storage)))))

(defn json-resp [status body serde]
  {:body (rpc/json-serialize serde body)
   :status status
   :headers {"Content-Type" "application/json"}})

(defn process [req session]
  (try
    (let [{::mcp/keys [dispatch-table serde]} @session
          msg (slurp (:body req))
          _ (log/debug "Processing request" msg)
          parsed (rpc/parse-string msg serde)
          handle #(rpc/handle-parsed % dispatch-table session)]
      (pcatch (if (vector? parsed)
                (->> (keep handle parsed)
                     rpc/combine-futures)
                (handle parsed))
              #(do (log/error %)
                   (c/internal-error nil (ex-message %)))))
    (catch Exception e
      (log/error e)
      (c/internal-error nil (ex-message e)))))

(defn session-creation-fn [session-template client-req-timeout-ms]
  (let [session (atom @session-template)]
    (swap! session assoc
           ::mcp/send-to-client (sse/send-to-client-fn session client-req-timeout-ms)
           ::mcp/q (ConcurrentLinkedQueue.))
    (add-watch session ::watcher handler/change-watcher)))

(defn handle-init
  "Handle Init calls"
  [req session sessions]
  (let [{::mcp/keys [dispatch-table serde]} @session
        msg (slurp (:body req))
        _ (log/debug "Init msg=" msg)
        parsed (rpc/parse-string msg serde)]
    (if (and (= :post (:request-method req))
             (= :request (:item-type parsed))
             (= "initialize" (:method parsed))
             (some? (:id parsed)))
      (let [new-session (create-session sessions)]
        (-> (json-resp 200 (rpc/handle-parsed parsed dispatch-table new-session) serde)
            (assoc-in [:headers "Mcp-Session-Id"] (::mcp/id @new-session))))
      (json-resp 400 "Bad Request" serde))))

(defn- do-client-req-cleanup
  "Performs client request cleanup."
  [last-cleanup timeout-ms]
  (let [t (System/currentTimeMillis)
        t' (swap! last-cleanup #(if (= (quot % 1000) (quot t 1000)) % t))]
    (when (= t t')
      (locking rpc/client-req-queue
        (rpc/cleanup-requests (or timeout-ms 120000))))))

(defn- parse-param-session-id [req]
  (some-> (:query-string req)
          (re-find #"sessionId=(.*)")
          second))

(defn handle-common
  "Handle to common cases, returns a session."
  [req origins sessions session-template endpoint sync?]
  (try
    (if (origins (get-in req [:headers "origin"]))
      (if-let [session-id (or (get-in req [:headers "mcp-session-id"])
                              (parse-param-session-id req))]
        (if-let [session (get-session sessions session-id)]
          (case (:request-method req)
            :get (do (.close (:body req))
                     {:status 200
                      :body (sse/get-resp session sync? endpoint)
                      :headers sse/streaming-headers})
            :delete (do (delete-session sessions session-id)
                        {:status 200 :body "" :headers {}})
            :post (papply (process req session)
                          (fn [resp]
                            (if (empty? resp)
                              {:status 202 :body nil :headers {"Content-Type" "application/json"}}
                              (if endpoint
                                ;; assume SSE transport and that there is an active GET connection
                                {:status 200
                                 :body (rpc/json-serialize (::mcp/serde @session) resp)
                                 :headers {"Content-Type" "application/json"}}
                                {:status 200
                                 :body (sse/post-resp session resp sync?)
                                 :headers sse/streaming-headers}))))
            {:status 405 :body "Method Not Allowed" :headers {}})
          (json-resp 404 "Not Found" (::mcp/serde @session-template)))
        (handle-init req session-template sessions))
      {:status 403 :body "Forbidden" :headers {}})
    (catch Exception e
      (log/error e)
      (throw e))))

(defn ring-handler
  "HTTP Streaming handler.

    Creates a Ring handler with session used as a template for new sessions

  - allowed-origins is a collection of Origin headers that are permitted on request. If nil,
    it will permit all origins
  - client-req-timeout-ms is timeout for requests from server to client."
  [session-template {:keys [allowed-origins client-req-timeout-ms endpoint] :as _opts}]
  (let [last-cleanup (atom 0)
        origins (if allowed-origins (set allowed-origins) (constantly true))
        sessions (->MemorySessionStore #(session-creation-fn session-template client-req-timeout-ms) (ConcurrentHashMap.))]
    (fn
      ([req]
       (do-client-req-cleanup last-cleanup client-req-timeout-ms)
       (let [resp (handle-common req origins sessions session-template endpoint true)]
         (if (instance? CompletableFuture resp) @resp resp)))
      ([req respond raise]
       (do-client-req-cleanup last-cleanup client-req-timeout-ms)
       (papply (handle-common req origins sessions session-template endpoint false)
               respond)))))