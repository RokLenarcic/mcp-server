(ns org.clojars.roklenarcic.mcp-server.handler.common
  "This namespace provides common functionality shared across all MCP handlers.
   It includes protocol conversion, request validation, client communication,
   and session management utilities."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.logging :as h.logging]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [?assoc]])
  (:import (java.io ByteArrayOutputStream IOException InputStream OutputStream)
           (java.util Base64 UUID)
           (java.util.concurrent CompletableFuture ConcurrentHashMap)))

(def ^ConcurrentHashMap client-progress
  "Map of progress tokens to callback function"
  (ConcurrentHashMap.))

(defn send-request
  "Sends a request to the client and returns a CompletableFuture for the response.

   This also handles progress callback, unlike the json-rpc version.

   Parameters:
   - rpc-session: the session atom
   - method: request method name
   - params: (optional) request parameters
   - progress-callback: optional progress callback

   Returns a CompletableFuture that will complete with the client's response."
  [rpc-session method params progress-callback]
  (let [token (when progress-callback (str (UUID/randomUUID)))
        params (if token
                 (do (.put client-progress token progress-callback)
                     (assoc-in params [:_meta :progressToken] token))
                 params)
        cancel-handler #(rpc/send-notification rpc-session "notifications/cancelled" {:requestId %})]
    (cond-> (rpc/send-request rpc-session method params cancel-handler)
      token (.whenComplete (fn [_ _] (.remove client-progress token))))))

(declare create-req-session)

(defn to-role-list
  "Converts role keywords to string vectors for MCP protocol.
   
   Parameters:
   - roles: single role keyword or vector of role keywords (:user, :assistant)
   
   Returns a vector of role strings, or nil if roles is nil."
  [roles]
  (when roles
    (mapv #(case %
             :user "user"
             :assistant "assistant"
             (str %))
          (if (sequential? roles) roles [roles]))))

(defn base64-encode
  "Encodes binary content to base64 string.
   
   Parameters:
   - c: content to encode (string, byte array, or InputStream)
   
   Returns base64 encoded string."
  [c]
  (cond
    (string? c) c
    (bytes? c) (.encodeToString (Base64/getEncoder) c)
    :else (let [out (ByteArrayOutputStream.)]
            (io/copy c out)
            (base64-encode (.toByteArray out)))))

(defn proto->resource
  "Converts a protocol resource object to MCP wire format.
   
   Parameters:
   - base-uri: base URI to use if resource doesn't specify one
   - o: resource object implementing ResourceResponse protocol
   
   Returns a map in MCP resource format."
  [base-uri o]
  (let [body (p/-res-body o)
        mime-type (p/-res-mime o)
        uri (p/-res-uri o)]
    (if (string? body)
      (?assoc {}
              :uri (or uri base-uri)
              :mimeType mime-type
              :text body)
      (if (or (bytes? body) (instance? InputStream body))
        (?assoc {}
                :uri (or uri base-uri)
                :mimeType mime-type
                :blob (base64-encode body))
        (do
          (log/error "Cannot convert resource body of type:" (class body))
          (throw (ex-info (format "Cannot convert %s to a resource" (class body)) {})))))))

(defn proto->content
  "Converts a protocol content object to MCP wire format.
   
   Parameters:
   - o: content object (Content, ResourceResponse, or string)
   
   Returns a map in MCP content format."
  [o]
  (let [o (cond
            (satisfies? p/Content o) o
            (string? o) (c/text-content o)
            (satisfies? p/ResourceResponse o) (c/embedded-content o)
            :else (c/text-content (str o)))
        priority (p/-con-priority o)
        audience (p/-con-audience o)
        annotations (?assoc nil
                            :priority priority
                            :audience (to-role-list audience))]
    ;;			@JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource"),
    ;;			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link") })
    (cond
      (satisfies? p/ImageContent o) (?assoc {:type "image"}
                                            :annotations annotations
                                            :data (base64-encode (p/-con-data o))
                                            :mimeType (p/-con-mime-type o))
      (satisfies? p/AudioContent o) (?assoc {:type "audio"}
                                            :annotations annotations
                                            :data (base64-encode (p/-aud-data o))
                                            :mimeType (p/-aud-mime-type o))

      (satisfies? p/TextContent o) (?assoc {:type "text"}
                                           :annotations annotations
                                           :text (p/-con-text o))

      (satisfies? p/ResourceResponse o) (?assoc {:type "resource"}
                                                :annotations annotations
                                                :resource (proto->resource nil o)))))

(defn ->content-vector
  "Converts content objects to a vector of MCP content format.
   
   Parameters:
   - o: content object(s) - can be single object, vector, or ToolErrorResponse
   
   Returns a vector of content maps in MCP format."
  [o]
  (log/trace "Converting to content vector - input type:" (type o))
  (cond
    (satisfies? p/ToolErrorResponse o) (->content-vector (p/-err-contents o))
    (sequential? o) (mapv proto->content o)
    :else (->content-vector [o])))

(defn proto->message
  "Converts a protocol message object to MCP wire format.
   
   Parameters:
   - o: message object (Message or Content)
   
   Returns a map in MCP message format."
  [o]
  (let [role (when (satisfies? p/Message o) (p/-msg-role o))
        content (if (satisfies? p/Message o) (p/-msg-content o) o)]
    (?assoc {:content (proto->content content)}
            :role (first (to-role-list role)))))

(defn wrap-check-init
  "Middleware that ensures the session is initialized."
  [handler]
  (fn check-params [rpc-session req-meta params]
    (if (::mcp/initialized? @rpc-session)
      (handler rpc-session req-meta params)
      (c/invalid-params "Session not initialized."))))

(defn- list-roots-req
  "Sends a request to the client to list its root directories.
   
   Parameters:
   - rpc-session: the session atom
   - progress-callback: progress callback
   
   Returns a CompletableFuture containing a vector of root objects."
  [rpc-session progress-callback]
  (log/trace "Requesting root list from client")
  (.thenApply
   ^CompletableFuture (send-request rpc-session "roots/list" nil progress-callback)
   (fn [{:keys [roots]}]
     (log/debug "Received roots response - count:" (if (map? roots) 1 (count roots)))
     (if (map? roots) [roots] (vec roots)))))

(def handle-changed-root
  "Handler for root change notifications from the client."
  (wrap-check-init
   (fn [rpc-session req-meta params]
     (log/debug "Client reported root directory changes")
     (when-let [cb (-> @rpc-session ::mcp/handlers :roots-changed-callback)]
       (cb (create-req-session rpc-session req-meta params)))
     (swap! rpc-session dissoc ::mcp/roots)
     nil)))

(def handle-progress
  "Handler for progress notification from the client."
  (wrap-check-init
   (fn [rpc-session req-meta params]
     (log/debug "Client reported progress")
     (when-let [token (:progressToken params)]
       (when-let [cb (.get client-progress token)]
         (cb params)))
     nil)))

(def handle-request-cancelled
  (wrap-check-init
   (fn [rpc-session req-meta {:keys [requestId reason]}]
     (log/infof "Request %s cancelled: %s" requestId reason)
     (rpc/update-inflight rpc-session
                          (fn [in-flight id]
                            (when-let [^CompletableFuture fut (get in-flight id)]
                              (.complete fut reason))
                            (dissoc in-flight id))
                          requestId)
     nil)))

(defn list-roots
  "Lists the client's root directories, using caching when appropriate.
   
   Parameters:
   - rpc-session: the session atom
   - progress-callback: callback function for
   
   Returns a CompletableFuture containing a vector of root objects."
  [rpc-session progress-callback]
  (let [{::mcp/keys [client-capabilities]} @rpc-session]
    (log/debug "Listing client roots - capabilities:" (keys client-capabilities))
    (if (:roots client-capabilities)
      (if (-> client-capabilities :roots :listChanged)
        ;; Client supports list change notifications, use cached roots
        ;; use a delay to not start a request immediately (what if swap fails and repeats?)
        (let [state (swap! rpc-session update ::mcp/roots #(or % (delay (list-roots-req rpc-session progress-callback))))]
          (log/trace "Using cached/delayed roots request")
          @(::mcp/roots state))
        ;; Client doesn't notify of changes, always request fresh
        (do
          (log/trace "Client doesn't support list change notifications, requesting fresh roots")
          (list-roots-req rpc-session progress-callback)))
      (do
        (log/trace "Client doesn't support roots capability, returning empty list")
        (CompletableFuture/completedFuture [])))))

(defn do-sampling
  "Requests the client to perform LLM sampling/completion.
   
   Parameters:
   - rpc-session: the session atom
   - sampling-request: map containing :messages, :model-preferences, :system-prompt, :max-tokens
   
   Returns a CompletableFuture containing the sampling result."
  [rpc-session {:keys [messages model-preferences system-prompt max-tokens]} progress-callback]
  (let [{:keys [hints intelligence-priority speed-priority]} model-preferences]
    (log/trace "Requesting sampling from client - messages:" (count (if (sequential? messages) messages [messages]))
               "max-tokens:" max-tokens)
    (send-request rpc-session
                  "sampling/createMessage"
                  (?assoc {:messages (mapv proto->message (if (sequential? messages) messages [messages]))}
                          :modelPreferences (?assoc nil
                                                    :hints hints
                                                    :intelligencePriority intelligence-priority
                                                    :speedPriority speed-priority)
                          :systemPrompt system-prompt
                          :maxTokens max-tokens)
                  progress-callback)))

(defn change-watcher
  "Watcher function that monitors session changes and sends notifications to client."
  [k rpc-session o n]
  (let [old-handlers (::mcp/handlers o)
        new-handlers (::mcp/handlers n)
        initialized? (::mcp/initialized? o)]
    (when (and initialized? (not= (:tools old-handlers) (:tools new-handlers)))
      (log/debug "Tools changed, notifying client")
      (rpc/send-notification rpc-session "notifications/tools/list_changed" nil))
    (when (and initialized? (not= (:prompts old-handlers) (:prompts new-handlers)))
      (log/debug "Prompts changed, notifying client")
      (rpc/send-notification rpc-session "notifications/prompts/list_changed" nil))
    (when (and (::mcp/os o) (not (identical? (::mcp/os o) (::mcp/os n))))
      (log/debug "Output stream changed, closing old stream")
      (try
        (.close ^OutputStream (::mcp/os o))
        (catch IOException e
          (log/info e "Error closing old output stream"))))))

(defn notify-progress
  [rpc-session progress-token msg]
  (log/debugf "Notifying progress on token %s: %s" progress-token msg)
  (rpc/send-notification rpc-session "notifications/progress" (assoc msg :progressToken progress-token)))

(defn create-req-session
  "Creates a RequestExchange object from a session atom.
   
   Parameters:
   - rpc-session: the session atom
   - req-meta: request metadata, including at least ::mcp/request-id if available
   
   Returns a RequestExchange object that handlers can use to interact with the client."
  [rpc-session req-meta params]
  (reify c/RequestExchange
    (req-meta [this] req-meta)
    (client-spec [this]
      (let [{::mcp/keys [client-info client-capabilities]} @rpc-session]
        {:info client-info :capabilities client-capabilities}))
    (get-session [this] rpc-session)
    (log-msg [this level logger msg data]
      (h.logging/do-log rpc-session level logger msg data))
    (list-roots [this] (list-roots rpc-session nil))
    (list-roots [this progress-callback] (list-roots rpc-session progress-callback))
    (sampling [this req]
      (when (-> @rpc-session ::mcp/client-capabilities :sampling)
        (do-sampling rpc-session req nil)))
    (sampling [this req progress-callback]
      (when (-> @rpc-session ::mcp/client-capabilities :sampling)
        (do-sampling rpc-session req progress-callback)))
    (report-progress [this msg]
      (let [progress-token (get-in params [:_meta :progressToken])]
        (when progress-token (notify-progress rpc-session progress-token msg))
        (some? progress-token)))
    (req-cancelled-future [this]
      (or (-> @rpc-session ::mcp/in-flight (get (-> req-meta ::mcp/request-id)))
          (CompletableFuture/completedFuture nil)))))
