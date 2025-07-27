(ns org.clojars.roklenarcic.mcp-server.server.http
  "This namespace provides HTTP-based transport for the MCP server using Ring.
  It handles HTTP requests, session management, CORS, and integrates with SSE
  for streaming communication. Supports both synchronous and asynchronous request handling."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.server.sse :as sse]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply]])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent CompletableFuture ConcurrentHashMap ConcurrentLinkedQueue)))

(defn- new-session-id []
  (let [buffer (byte-array 32)]
    (.nextBytes (SecureRandom.) buffer)
    (.encodeToString (Base64/getUrlEncoder) buffer)))

(defprotocol Sessions
  "Protocol for session storage implementations."
  (get-session [this session-id]
  "Retrieves a session by ID. Returns nil if not found.")
  (delete-session [this session-id] 
    "Removes a session by ID. Returns the removed session or nil.")
  (add-session [this session]
    "Adds a session to the store, adds mcp/id to the session, returns the session atom.")
  (all-sessions [this] 
    "Returns a collection of all active sessions."))

(defrecord MemorySessionStore
  [^ConcurrentHashMap storage]
  Sessions
  (get-session [this session-id] (.get storage session-id))
  (delete-session [this session-id]
    (log/trace "Deleting session:" session-id)
    (.remove storage session-id))
  (add-session [this session]
    (let [session-id (new-session-id)]
      (swap! session assoc
             ::mcp/id session-id
             ::mcp/session-creation-time (System/currentTimeMillis))
      (.put storage session-id session)
      (log/debug "Session created with ID:" session-id)
      session))
  (all-sessions [this] 
    (log/debug "Retrieving all sessions - count:" (.size storage))
    (iterator-seq (.iterator (.values storage)))))

(defn json-resp
  "Creates a JSON HTTP response.
   
   Parameters:
   - status: HTTP status code
   - body: response body to serialize as JSON
   - serde: JSON serialization implementation
   
   Returns a Ring response map."
  [status body serde]
  {:body (rpc/json-serialize serde body)
   :status status
   :headers {"Content-Type" "application/json"}})

(defn process
  "Processes an HTTP request containing MCP JSON-RPC messages.
   
   Parameters:
   - req: Ring request map
   - session: session atom for this request
   
   Returns a processed response or CompletableFuture."
  [req session]
  (try
    (let [{::mcp/keys [dispatch-table serde]} @session
          msg (slurp (:body req))
          _ (log/debug "Processing request" msg)
          parsed (rpc/parse-string msg serde)
          handle #(rpc/handle-parsed % dispatch-table session req)]
      (if (vector? parsed)
        (->> (keep handle parsed) rpc/combine-futures)
        (handle parsed)))
    (catch Exception e
      (log/error e)
      (c/internal-error nil (ex-message e)))))

(defn create-session
  "Creates a session for HTTP transport.

   Parameters:
   - session-map: a map that is basis for a session"
  [session-map]
  (let [session (atom session-map)]
    (swap! session assoc
           ::mcp/send-to-client (sse/send-to-client-fn session)
           ::mcp/q (ConcurrentLinkedQueue.))
    (add-watch session ::watcher handler/change-watcher)
    session))

(defn handle-init
  "Handles MCP initialization requests that create new sessions.
   
   Parameters:
   - req: Ring request map
   - sessions: session storage implementation
   - session-map: map that can be used to create a session
   
   Returns a Ring response map with session ID header."
  [req sessions session-map]
  (let [{::mcp/keys [dispatch-table serde]} session-map
        msg (slurp (:body req))
        _ (log/trace "Init message length:" (count msg))
        parsed (rpc/parse-string msg serde)]
    
    (if (and (= :post (:request-method req))
             (= :request (:item-type parsed))
             (= "initialize" (:method parsed))
             (some? (:id parsed)))
      (do
        (log/debug "Valid initialization request, creating new session")
        (let [new-session (add-session sessions (create-session session-map))
              response (rpc/handle-parsed parsed dispatch-table new-session req)
              session-id (::mcp/id @new-session)]
          (log/debug "Session initialized successfully with ID:" session-id)
          (-> (json-resp 200 response serde)
              (assoc-in [:headers "Mcp-Session-Id"] session-id))))
      (json-resp 400 "Bad Request" serde))))

(defn- parse-param-session-id [req]
  (some->> (:query-string req)
           (re-find #"sessionId=(.*)")
           second))

(defn handle-common
  "Handles common HTTP request patterns for MCP over HTTP.
   
   Parameters:
   - req: Ring request map
   - origins: set of allowed origins or function that returns true for all
   - sessions: session storage implementation
   - session-map: basic session map, basis for new sessions
   - endpoint: optional endpoint information for SSE
   - sync?: whether to use synchronous processing
   
   Returns a Ring response map or CompletableFuture."
  [req origins sessions session-map endpoint sync?]
  (try
    (let [origin (get-in req [:headers "origin"])]
      (log/debug "Handling HTTP request - method:" (:request-method req) "origin:" origin "sync:" sync?)
      
      (if (origins origin)
        (if-let [session-id (or (get-in req [:headers "mcp-session-id"])
                                (parse-param-session-id req))]
          (if-let [session (get-session sessions session-id)]
            (do
              (log/trace "Found existing session:" session-id)
              (case (:request-method req)
                :get 
                (do
                  (log/trace "Handling GET request for SSE connection")
                  (.close (:body req))
                  {:status 200
                   :body (sse/get-resp session sync? endpoint)
                   :headers sse/streaming-headers})
                
                :delete 
                (do
                  (log/trace "Handling DELETE request - removing session:" session-id)
                  (delete-session sessions session-id)
                  {:status 200 :body "" :headers {}})
                
                :post 
                (do
                  (log/trace "Handling POST request for message processing")
                  (papply (process req session)
                          (fn [resp]
                            (cond
                              (empty? resp)
                              (do
                                (log/trace "Empty response, returning 202")
                                {:status 202 :body nil :headers {"Content-Type" "application/json"}})
                              
                              endpoint
                              (do
                                (log/trace "Endpoint mode - returning JSON response")
                                {:status 200
                                 :body (rpc/json-serialize (::mcp/serde @session) resp)
                                 :headers {"Content-Type" "application/json"}})
                              
                              :else
                              (do
                                (log/trace "SSE mode - returning streaming response")
                                {:status 200
                                 :body (sse/post-resp session (rpc/json-serialize (::mcp/serde @session) resp) sync?)
                                 :headers sse/streaming-headers})))))
                
                (do
                  (log/warn "Method not allowed:" (:request-method req))
                  {:status 405 :body "Method Not Allowed" :headers {}})))
            (do
              (log/debug "Session not found:" session-id "- returning 404")
              (json-resp 404 "Not Found" (::mcp/serde session-map))))
          (do
            (log/trace "No session ID provided - attempting initialization")
            (handle-init req sessions session-map)))
        {:status 403 :body "Forbidden" :headers {}}))
    (catch Exception e
      (log/error e "Error handling HTTP request")
      (throw e))))

(defn memory-sessions-store
  "A Sessions implementation that stores sessions in a lookup map."
  []
  (->MemorySessionStore (ConcurrentHashMap.)))

(defn ring-handler
  "Creates a Ring handler for MCP over HTTP with SSE streaming support.

   Parameters:
   - session-template: template session atom for creating new sessions
   - sessions: a Sessions instance to store the sessions
   - opts: options map containing:
     - :allowed-origins: collection of allowed Origin headers (nil permits all)
     - :client-req-timeout-ms: timeout for client requests (default: 120000)
     - :endpoint: optional endpoint information for SSE connections

   Returns a Ring handler function that supports both sync and async operation."
  [session-template sessions {:keys [allowed-origins client-req-timeout-ms endpoint] :as opts}]
  (log/debug "Creating Ring handler for MCP over HTTP")
  (log/debug "Handler options:" opts)
  (let [origins (if allowed-origins (set allowed-origins) (constantly true))]
    (fn
      ([req]
       (rpc/cleanup-requests (or client-req-timeout-ms 120000))
       (let [resp (handle-common req origins sessions (assoc @session-template
                                                        ::mcp/timeout-ms client-req-timeout-ms) endpoint true)]
         (if (instance? CompletableFuture resp) @resp resp)))
      ([req respond raise]
       (rpc/cleanup-requests (or client-req-timeout-ms 120000))
       (papply (handle-common req origins sessions (assoc @session-template
                                                     ::mcp/timeout-ms client-req-timeout-ms) endpoint false)
               respond)))))