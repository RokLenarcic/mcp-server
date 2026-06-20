(ns org.clojars.roklenarcic.mcp-server.server.http
  "Streamable HTTP transport for MCP 2025-06-18.

  A single endpoint exposes three HTTP methods:
  - POST   - client to server JSON-RPC requests, notifications, and responses
  - GET    - long-lived SSE stream for server to client messages
  - DELETE - terminate the session

  After the initialize handshake the server returns a Mcp-Session-Id header;
  clients MUST include that header on every subsequent request and SHOULD
  include MCP-Protocol-Version: 2025-06-18."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.handler.init :as init]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as parse]
            [org.clojars.roklenarcic.mcp-server.server.sse :as sse]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply]]
            [ring.core.protocols :as ring])
  (:import (java.io OutputStream)
           (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent CompletableFuture ConcurrentHashMap LinkedBlockingDeque)
           (org.clojars.roklenarcic.mcp_server.server.sse StreamableSession)))

;; --- session id generation -------------------------------------------------

(defn- new-session-id []
  (let [buffer (byte-array 32)]
    (.nextBytes (SecureRandom.) buffer)
    (.encodeToString (Base64/getUrlEncoder) buffer)))

;; --- Sessions protocol & in-memory implementation --------------------------

(defprotocol Sessions
  "Storage for HTTP transport sessions."
  (get-session [this session-id]
    "Returns the StreamableSession for `session-id`, or nil.")
  (delete-session [this session-id]
    "Removes the session and returns the removed StreamableSession, or nil.")
  (add-session [this ss]
    "Registers `ss` (a StreamableSession) in the store using ::mcp/id from
    its session atom; returns `ss`. Callers must have set ::mcp/id beforehand
    (typically via `create-session`).")
  (all-sessions [this]
    "Returns a seq of all active StreamableSessions."))

(defrecord MemorySessionStore [^ConcurrentHashMap storage]
  Sessions
  (get-session [_ session-id] (.get storage session-id))
  (delete-session [_ session-id]
    (log/trace "Deleting session:" session-id)
    (.remove storage session-id))
  (add-session [_ ss]
    (let [session-id (::mcp/id @(:session ss))]
      (assert session-id "session must have ::mcp/id set before being added")
      (.put storage session-id ss)
      (log/debug "Session registered:" session-id)
      ss))
  (all-sessions [_]
    (iterator-seq (.iterator (.values storage)))))

(defn memory-sessions-store
  "An in-memory Sessions store backed by ConcurrentHashMap."
  []
  (->MemorySessionStore (ConcurrentHashMap.)))

;; --- header helpers --------------------------------------------------------

(defn- header
  "Lowercase header lookup."
  [req name]
  (get-in req [:headers name]))

(defn- accepts?
  "True when `accept-header` lists `mime-type` or */*."
  [accept-header mime-type]
  (boolean
    (when accept-header
      (some (fn [^String part]
              (let [media (-> part (str/split #";") first str/trim)]
                (or (= media mime-type) (= media "*/*"))))
            (str/split accept-header #",")))))

(defn- json-content-type?
  "True when the request declares Content-Type: application/json."
  [req]
  (boolean
    (when-let [ct (header req "content-type")]
      (str/starts-with? (str/trim ct) "application/json"))))

(defn- protocol-version-ok?
  "Accept the request when MCP-Protocol-Version is absent or matches a
  supported version."
  [req]
  (let [hdr (header req "mcp-protocol-version")]
    (or (nil? hdr) (boolean (init/allowed-protocol-versions hdr)))))

;; --- response builders -----------------------------------------------------

(defn json-resp
  "Builds a Ring response with `body` serialized as JSON."
  [status body serde]
  {:status status
   :body (rpc/json-serialize serde body)
   :headers {"Content-Type" "application/json"}})

(defn- empty-resp [status]
  {:status status :body "" :headers {}})

(defn- error-resp
  "Builds a JSON Ring response carrying a JSON-RPC error object."
  ([status code message data serde]
   (error-resp status code message data nil serde))
  ([status code message data id serde]
   (json-resp status (rpc/make-error-response code message data id) serde)))

;; --- session creation ------------------------------------------------------

(defn create-session
  "Builds a fresh StreamableSession from `session-map`, assigning a new
  `::mcp/id`, wiring in the SSE send-to-client function, the bounded outbound
  queue, and the change watcher. The session is NOT added to any store; the
  caller decides when (and whether) to register it via `add-session`.

  The outbound queue is bounded by `::mcp/sse-queue-capacity` (default 1024)
  so a slow or disconnected client cannot grow memory without bound; when
  the queue is full the oldest queued message is dropped to make room.

  The replay buffer (held on the StreamableSession record, not in the atom)
  is bounded by `::mcp/sse-replay-capacity` (default matches
  `::mcp/sse-queue-capacity`) and holds events that were written successfully
  so they can be re-sent on a GET reconnect that supplies a `Last-Event-ID`
  header. When full the oldest entry is dropped."
  [session-map]
  (let [capacity (or (::mcp/sse-queue-capacity session-map) 1024)
        replay-cap (or (::mcp/sse-replay-capacity session-map) capacity)
        session (atom (-> session-map
                          (assoc ::mcp/id (new-session-id)
                                 ::mcp/session-creation-time (System/currentTimeMillis)
                                 ::mcp/q (LinkedBlockingDeque. (int capacity)))
                          (dissoc ::mcp/sse-queue-capacity ::mcp/sse-replay-capacity)))]
    (add-watch session ::watcher handler/change-watcher)
    (sse/make-streamable-session session replay-cap)))

;; --- request body parsing --------------------------------------------------

(defn- parse-body [req serde]
  (let [msg (slurp (:body req))]
    (log/debug "Received message length:" (count msg))
    (rpc/parse-string msg serde)))

;; --- per-method handlers ---------------------------------------------------

(defn- post-response
  "Maps a dispatched POST result to a Ring response, given the original
  parsed item-type. Argument order matches `papply`: the dispatched value
  arrives first."
  [resp item-type serde]
  (cond
    ;; Parse / invalid-request errors -> 400 + JSON-RPC error body.
    (= :error item-type)
    (json-resp 400 resp serde)

    ;; Notifications and client responses -> 202 no body.
    (nil? resp)
    (empty-resp 202)

    ;; Regular request response -> 200 + JSON body.
    :else
    (json-resp 200 resp serde)))

(defn- post-sse-resp
  "Streamable response body for a POST whose parsed item is a JSON-RPC
  request.

  Per MCP 2025-06-18 Streamable HTTP, the server MAY (and this transport
  does) return an SSE stream as the POST response so that server-initiated
  messages produced during dispatch can piggyback on the same connection.
  Concretely, the body:

  1. Flushes the response so the client sees `200 + text/event-stream`
     headers before any handler work begins.
  2. Registers the response OutputStream as a request-scoped POST-SSE
     stream and binds it to `sse/*post-stream*` for the duration of the
     dispatch so that server-initiated messages produced by synchronous
     handler code route deterministically to this exact stream first.
  3. Invokes the supplied `dispatch` thunk (a zero-arg closure over
     `rpc/handle-parsed`). Three outcomes:
     - Non-future result: write + deregister + close inline.
     - CompletableFuture in sync Ring mode: block on `@result`, write
       + deregister + close, then return.
     - CompletableFuture in async Ring mode: attach a `.thenApply`
       callback that writes + deregisters + closes the OutputStream,
       and return immediately.
  4. Writes the JSON-RPC response as a single SSE event and atomically
     deregisters the stream under `send-mutex` via
     `sse/write-post-response-and-deregister`.
  5. Closes the response OutputStream so the HTTP response finishes —
     in async mode that close completes the underlying `AsyncContext`."
  [^StreamableSession ss dispatch serde sync?]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [_ _ os]
      (.flush ^OutputStream os)
      (sse/register-post-stream ss os)
      (let [close-stream (fn []
                           (try (.close ^OutputStream os)
                                (catch Throwable e
                                  (log/debug e "POST-SSE OS close failed"))))]
        (try
          (let [complete (fn [resp]
                           (try
                             (sse/write-post-response-and-deregister
                               ss os (when resp (rpc/json-serialize serde resp)))
                             (catch Throwable e
                               (log/error e "POST-SSE write failed")
                               (sse/deregister-post-stream ss os))
                             (finally
                               (close-stream))))
                result (papply (binding [sse/*post-stream* os] (dispatch)) complete)]
            (cond-> result (and sync? (instance? CompletableFuture result)) deref))
          (catch Throwable e
            (log/error e "POST-SSE dispatch failed")
            (sse/deregister-post-stream ss os)
            (close-stream)))))))

(defn- handle-init-post
  "Handles a POST that arrived without an Mcp-Session-Id header; the only
  acceptable payload is a valid initialize request.

  The new session is created up front so the initialize handler can mutate
  it, but it is only registered with the session store (and its
  Mcp-Session-Id returned to the client) when the handler produces a success
  response. A handler-level error (e.g. unsupported protocolVersion) leaves
  the store untouched."
  [req sessions session-map]
  (let [serde (::mcp/serde session-map)
        parsed (parse-body req serde)]
    (cond
      (= :error (:item-type parsed))
      (json-resp 400 {:jsonrpc "2.0" :error (:error parsed) :id (:id parsed)} serde)

      (and (= :request (:item-type parsed))
           (= "initialize" (:method parsed)))
      (let [ss (create-session session-map)
            session (:session ss)]
        (papply (rpc/handle-parsed parsed (::mcp/dispatch-table session-map) session req)
                (fn [r]
                  (if (and (map? r) (not (:error r)))
                    (do (add-session sessions ss)
                        (-> (json-resp 200 r serde)
                            (assoc-in [:headers "Mcp-Session-Id"] (::mcp/id @session))))
                    ;; Handler-level error: do not register the session and
                    ;; do not send back an Mcp-Session-Id header.
                    (json-resp 200 r serde)))))

      :else
      (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                  "Mcp-Session-Id header required" (:id parsed) serde))))

(defn- handle-session-post
  "Handles a POST routed to an existing session: parse, dispatch, respond.

  Request items return an SSE-streamed response (handled inside
  `post-sse-resp` so that mid-dispatch server-initiated messages can
  piggyback on the same connection per MCP 2025-06-18). Notifications
  and client responses still return 202 with an empty body; parse and
  invalid-request errors still return 400 with a JSON error body.

  `sync?` flows through to `post-sse-resp` so it can decide whether to
  block on a CompletableFuture result (sync adapter) or attach a callback
  and return immediately (async adapter)."
  [req ^StreamableSession ss sync?]
  (let [session (:session ss)
        {::mcp/keys [dispatch-table serde]} @session
        parsed (parse-body req serde)
        dispatch #(rpc/handle-parsed parsed dispatch-table session req)]
    (if (= :request (:item-type parsed))
      {:status 200
       :headers sse/streaming-headers
       :body (post-sse-resp ss dispatch serde sync?)}
      (papply (dispatch) post-response (:item-type parsed) serde))))

(defn- parse-event-id
  "Parses a `Last-Event-ID` header value into a Long; returns nil if blank,
  missing, or non-numeric."
  [s]
  (some-> s str/trim not-empty parse-long))

(defn- handle-get
  "Handles a GET routed to an existing session: open an SSE stream that
  carries server-originated requests and notifications. Honors a
  `Last-Event-ID` header by replaying buffered events with a higher
  event-id before live delivery resumes."
  [req ^StreamableSession ss sync?]
  (.close (:body req))
  (let [last-event-id (parse-event-id (header req "last-event-id"))]
    {:status 200
     :headers sse/streaming-headers
     :body (sse/get-resp ss sync? last-event-id)}))

;; --- routing ---------------------------------------------------------------

(defn- post-headers-error
  "Validates POST headers; returns a Ring error response if invalid, nil
  otherwise."
  [req serde]
  (cond
    (not (json-content-type? req))
    (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                "Content-Type must be application/json" serde)

    (let [accept (header req "accept")]
      (not (accepts? accept "text/event-stream")))
    (error-resp 406 parse/INVALID_REQUEST "Not Acceptable"
                "Accept must include text/event-stream"
                serde)))

(defn- route-post
  [req sessions {::mcp/keys [serde] :as session-map} sync?]
  (or (post-headers-error req serde)
      (if-let [session-id (header req "mcp-session-id")]
        (if-let [ss (get-session sessions session-id)]
          (if (protocol-version-ok? req)
            (handle-session-post req ss sync?)
            (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                        (str "Unsupported MCP-Protocol-Version: "
                             (header req "mcp-protocol-version"))
                        serde))
          (error-resp 404 parse/INVALID_REQUEST "Not Found"
                      "Session not found" serde))
        (handle-init-post req sessions session-map))))

(defn- route-get
  [req sessions {::mcp/keys [serde]} sync?]
  (if (accepts? (header req "accept") "text/event-stream")
    (if-let [session-id (header req "mcp-session-id")]
      (if-let [ss (get-session sessions session-id)]
        (if (protocol-version-ok? req)
          (handle-get req ss sync?)
          (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                      (str "Unsupported MCP-Protocol-Version: "
                           (header req "mcp-protocol-version"))
                      serde))
        (error-resp 404 parse/INVALID_REQUEST "Not Found"
                    "Session not found" serde))
      (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                  "Mcp-Session-Id header required" serde))
    (error-resp 406 parse/INVALID_REQUEST "Not Acceptable"
                "Accept must include text/event-stream" serde)))

(defn- route-delete
  [req sessions {::mcp/keys [serde]}]
  (if-let [session-id (header req "mcp-session-id")]
    (if-let [^StreamableSession ss (delete-session sessions session-id)]
      (do (sse/set-os! ss nil)
          (empty-resp 200))
      (error-resp 404 parse/INVALID_REQUEST "Not Found"
                  "Session not found" serde))
    (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                "Mcp-Session-Id header required" serde)))

(defn handle-common
  "Dispatches an HTTP request to the appropriate route after the origin check."
  [req origins sessions session-map sync?]
  (try
    (let [origin (header req "origin")
          method (:request-method req)]
      (log/debug "HTTP request - method:" method "origin:" origin "sync?:" sync?)
      (cond
        (not (origins origin))
        {:status 403 :body "Forbidden" :headers {}}

        (= :post method)   (route-post req sessions session-map sync?)
        (= :get method)    (route-get req sessions session-map sync?)
        (= :delete method) (route-delete req sessions session-map)

        :else
        {:status 405
         :body "Method Not Allowed"
         :headers {"Allow" "POST, GET, DELETE"}}))
    (catch Exception e
      (log/error e "Error handling HTTP request")
      (throw e))))

;; --- public API ------------------------------------------------------------

(defn ring-handler
  "Creates a Ring handler implementing MCP 2025-06-18 Streamable HTTP transport.

  Parameters:
  - session-template: an atom holding a session map used as a template for
    each new session
  - sessions: a Sessions instance for storing live sessions
  - opts: option map
    - :allowed-origins - collection of allowed Origin headers (nil permits all)
    - :client-req-timeout-ms - client request timeout in ms (default 120000)
    - :sse-queue-capacity - max number of messages buffered per session while
      no SSE stream is attached (default 1024). When full, the oldest queued
      message is dropped to make room for the newest.
    - :sse-replay-capacity - max number of successfully-written events
      retained per session for `Last-Event-ID` replay on reconnect
      (default: same as :sse-queue-capacity). When full, the oldest
      replay entry is dropped first.

  Returns a Ring handler that supports both 1-arity (synchronous) and
  3-arity (asynchronous) Ring operation."
  [session-template sessions {:keys [allowed-origins client-req-timeout-ms
                                     sse-queue-capacity sse-replay-capacity]}]
  (log/debug "Creating Ring handler for MCP Streamable HTTP")
  (let [origins (if allowed-origins (set allowed-origins) (constantly true))
        timeout (or client-req-timeout-ms 120000)
        capacity (or sse-queue-capacity 1024)
        replay-cap (or sse-replay-capacity capacity)
        session-map-of #(assoc @session-template
                               ::mcp/timeout-ms timeout
                               ::mcp/sse-queue-capacity capacity
                               ::mcp/sse-replay-capacity replay-cap)]
    (fn
      ([req]
       (rpc/cleanup-requests timeout)
       (let [resp (handle-common req origins sessions (session-map-of) true)]
         (if (instance? CompletableFuture resp) @resp resp)))
      ([req respond raise]
       (rpc/cleanup-requests timeout)
       (try
         (papply (handle-common req origins sessions (session-map-of) false) respond)
         (catch Exception e (raise e)))))))
