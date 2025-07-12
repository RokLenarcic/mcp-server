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
           (java.util Base64)
           (java.util.concurrent CompletableFuture)))

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
  (log/debug "Converting to content vector - input type:" (type o))
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
  (fn check-params [rpc-session params]
    (if (::mcp/initialized? @rpc-session)
      (handler rpc-session params)
      (c/invalid-params "Session not initialized."))))

(defn- list-roots-req
  "Sends a request to the client to list its root directories.
   
   Parameters:
   - rpc-session: the session atom
   
   Returns a CompletableFuture containing a vector of root objects."
  [rpc-session]
  (log/debug "Requesting root list from client")
  (.thenApply
    ^CompletableFuture (rpc/send-request rpc-session "roots/list" nil)
    (fn [{:keys [roots]}]
      (log/debug "Received roots response - count:" (if (map? roots) 1 (count roots)))
      (if (map? roots) [roots] (vec roots)))))

(def handle-changed-root
  "Handler for root change notifications from the client."
  (wrap-check-init
    (fn [rpc-session _]
      (log/debug "Client reported root directory changes")
      (when-let [cb (-> @rpc-session ::mcp/handlers :roots-changed-callback)]
        (cb (create-req-session rpc-session)))
      (swap! rpc-session dissoc ::mcp/roots)
      nil)))

(defn list-roots 
  "Lists the client's root directories, using caching when appropriate.
   
   Parameters:
   - rpc-session: the session atom
   
   Returns a CompletableFuture containing a vector of root objects."
  [rpc-session]
  (let [{::mcp/keys [client-capabilities]} @rpc-session]
    (log/debug "Listing client roots - capabilities:" (keys client-capabilities))
    (if (:roots client-capabilities)
      (if (-> client-capabilities :roots :listChanged)
        ;; Client supports list change notifications, use cached roots
        ;; use a delay to not start a request immediately (what if swap fails and repeats?)
        (let [state (swap! rpc-session update ::mcp/roots #(or % (delay (list-roots-req rpc-session))))]
          (log/debug "Using cached/delayed roots request")
          @(::mcp/roots state))
        ;; Client doesn't notify of changes, always request fresh
        (do
          (log/debug "Client doesn't support list change notifications, requesting fresh roots")
          (list-roots-req rpc-session)))
      (do
        (log/debug "Client doesn't support roots capability, returning empty list")
        (CompletableFuture/completedFuture [])))))

(defn do-sampling 
  "Requests the client to perform LLM sampling/completion.
   
   Parameters:
   - rpc-session: the session atom
   - sampling-request: map containing :messages, :model-preferences, :system-prompt, :max-tokens
   
   Returns a CompletableFuture containing the sampling result."
  [rpc-session {:keys [messages model-preferences system-prompt max-tokens]}]
  (let [{:keys [hints intelligence-priority speed-priority]} model-preferences]
    (log/debug "Requesting sampling from client - messages:" (count (if (sequential? messages) messages [messages]))
               "max-tokens:" max-tokens)
    (rpc/send-request rpc-session
                      "sampling/createMessage"
                      (?assoc {:messages (mapv proto->message (if (sequential? messages) messages [messages]))}
                              :modelPreferences (?assoc nil
                                                        :hints hints
                                                        :intelligencePriority intelligence-priority
                                                        :speedPriority speed-priority)
                              :systemPrompt system-prompt
                              :maxTokens max-tokens))))

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

(defn create-req-session 
  "Creates a RequestExchange object from a session atom.
   
   Parameters:
   - rpc-session: the session atom
   
   Returns a RequestExchange object that handlers can use to interact with the client."
  [rpc-session]
  (reify c/RequestExchange
    (client-spec [this]
      (let [{::mcp/keys [client-info client-capabilities]} @rpc-session]
        {:info client-info :capabilities client-capabilities}))
    (get-session [this] rpc-session)
    (log-msg [this level logger msg data]
      (h.logging/do-log rpc-session level logger msg data))
    (list-roots [this] (list-roots rpc-session))
    (sampling [this req] 
      (when (-> @rpc-session ::mcp/client-capabilities :sampling)
        (do-sampling rpc-session req)))))
