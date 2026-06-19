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
            [org.clojars.roklenarcic.mcp-server.util :refer [papply]])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent CompletableFuture ConcurrentHashMap LinkedBlockingDeque)
           (java.util.concurrent.atomic AtomicLong)))

;; --- session id generation -------------------------------------------------

(defn- new-session-id []
  (let [buffer (byte-array 32)]
    (.nextBytes (SecureRandom.) buffer)
    (.encodeToString (Base64/getUrlEncoder) buffer)))

;; --- Sessions protocol & in-memory implementation --------------------------

(defprotocol Sessions
  "Storage for HTTP transport sessions."
  (get-session [this session-id]
    "Returns the session atom for `session-id`, or nil.")
  (delete-session [this session-id]
    "Removes the session and returns the removed session atom, or nil.")
  (add-session [this session]
    "Registers `session` in the store using its existing ::mcp/id; returns the
    session atom. Callers must have set ::mcp/id beforehand (typically via
    `create-session`).")
  (all-sessions [this]
    "Returns a seq of all active session atoms."))

(defrecord MemorySessionStore [^ConcurrentHashMap storage]
  Sessions
  (get-session [_ session-id] (.get storage session-id))
  (delete-session [_ session-id]
    (log/trace "Deleting session:" session-id)
    (.remove storage session-id))
  (add-session [_ session]
    (let [session-id (::mcp/id @session)]
      (assert session-id "session must have ::mcp/id set before being added")
      (.put storage session-id session)
      (log/debug "Session registered:" session-id)
      session))
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
  "Builds a fresh HTTP session atom from `session-map`, assigning a new
  `::mcp/id`, wiring in the SSE send-to-client function, the bounded outbound
  queue, the bounded SSE replay buffer, and the change watcher. The session
  is NOT added to any store; the caller decides when (and whether) to
  register it via `add-session`.

  The outbound queue is bounded by `::mcp/sse-queue-capacity` (default 1024)
  so a slow or disconnected client cannot grow memory without bound; when
  the queue is full the oldest queued message is dropped to make room.

  The replay buffer is bounded by `::mcp/sse-replay-capacity` (default
  matches `::mcp/sse-queue-capacity`) and holds events that were written
  successfully so they can be re-sent on a GET reconnect that supplies a
  `Last-Event-ID` header. When full the oldest entry is dropped."
  [session-map]
  (let [capacity (or (::mcp/sse-queue-capacity session-map) 1024)
        replay-cap (or (::mcp/sse-replay-capacity session-map) capacity)
        session (atom session-map)
        send-to-client (sse/send-to-client-fn session)]
    (swap! session assoc
           ::mcp/id (new-session-id)
           ::mcp/session-creation-time (System/currentTimeMillis)
           ::mcp/send-mutex (Object.)
           ::mcp/sse-next-event-id (AtomicLong. 0)
           ::mcp/q (LinkedBlockingDeque. (int capacity))
           ::mcp/replay-buffer (LinkedBlockingDeque. (int replay-cap))
           ::mcp/send-to-client send-to-client)
    (add-watch session ::watcher handler/change-watcher)
    session))

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
      (let [session (create-session session-map)]
        (papply (rpc/handle-parsed parsed (::mcp/dispatch-table session-map) session req)
                (fn [r]
                  (if (and (map? r) (not (:error r)))
                    (do (add-session sessions session)
                        (-> (json-resp 200 r serde)
                            (assoc-in [:headers "Mcp-Session-Id"] (::mcp/id @session))))
                    ;; Handler-level error: do not register the session and
                    ;; do not send back an Mcp-Session-Id header.
                    (json-resp 200 r serde)))))

      :else
      (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                  "Mcp-Session-Id header required" (:id parsed) serde))))

(defn- handle-session-post
  "Handles a POST routed to an existing session: parse, dispatch, respond."
  [req session]
  (let [{::mcp/keys [dispatch-table serde]} @session
        parsed (parse-body req serde)]
    (papply (rpc/handle-parsed parsed dispatch-table session req)
            post-response (:item-type parsed) serde)))

(defn- parse-event-id
  "Parses a `Last-Event-ID` header value into a Long; returns nil if blank,
  missing, or non-numeric."
  [s]
  (when-let [trimmed (some-> s str/trim not-empty)]
    (try
      (Long/parseLong trimmed)
      (catch NumberFormatException _ nil))))

(defn- handle-get
  "Handles a GET routed to an existing session: open an SSE stream that
  carries server-originated requests and notifications. Honors a
  `Last-Event-ID` header by replaying buffered events with a higher
  event-id before live delivery resumes."
  [req session sync?]
  (.close (:body req))
  (let [last-event-id (parse-event-id (header req "last-event-id"))]
    {:status 200
     :headers sse/streaming-headers
     :body (sse/get-resp session sync? last-event-id)}))

(defn- handle-delete
  "Terminates the named session: removes it from the store and detaches any
  attached SSE stream so writers see ::mcp/os = nil and stop. Returns 200."
  [sessions session-id]
  (when-let [session (delete-session sessions session-id)]
    (some->> @session ::mcp/os (sse/close-os session)))
  (empty-resp 200))

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
      (not (and (accepts? accept "application/json")
                (accepts? accept "text/event-stream"))))
    (error-resp 406 parse/INVALID_REQUEST "Not Acceptable"
                "Accept must include application/json and text/event-stream"
                serde)))

(defn- route-post
  [req sessions session-map]
  (let [serde (::mcp/serde session-map)]
    (or (post-headers-error req serde)
        (if-let [session-id (header req "mcp-session-id")]
          (if-let [session (get-session sessions session-id)]
            (if (protocol-version-ok? req)
              (handle-session-post req session)
              (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                          (str "Unsupported MCP-Protocol-Version: "
                               (header req "mcp-protocol-version"))
                          serde))
            (error-resp 404 parse/INVALID_REQUEST "Not Found"
                        "Session not found" serde))
          (handle-init-post req sessions session-map)))))

(defn- route-get
  [req sessions session-map sync?]
  (let [serde (::mcp/serde session-map)]
    (cond
      (not (accepts? (header req "accept") "text/event-stream"))
      (error-resp 406 parse/INVALID_REQUEST "Not Acceptable"
                  "Accept must include text/event-stream" serde)

      :else
      (if-let [session-id (header req "mcp-session-id")]
        (if-let [session (get-session sessions session-id)]
          (if (protocol-version-ok? req)
            (handle-get req session sync?)
            (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                        (str "Unsupported MCP-Protocol-Version: "
                             (header req "mcp-protocol-version"))
                        serde))
          (error-resp 404 parse/INVALID_REQUEST "Not Found"
                      "Session not found" serde))
        (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                    "Mcp-Session-Id header required" serde)))))

(defn- route-delete
  [req sessions session-map]
  (let [serde (::mcp/serde session-map)]
    (if-let [session-id (header req "mcp-session-id")]
      (if (get-session sessions session-id)
        (handle-delete sessions session-id)
        (error-resp 404 parse/INVALID_REQUEST "Not Found"
                    "Session not found" serde))
      (error-resp 400 parse/INVALID_REQUEST "Invalid Request"
                  "Mcp-Session-Id header required" serde))))

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

        (= :post method)   (route-post req sessions session-map)
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
