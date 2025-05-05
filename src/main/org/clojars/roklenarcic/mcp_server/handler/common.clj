(ns org.clojars.roklenarcic.mcp-server.handler.common
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

(defn to-role-list [roles]
  (when roles
    (mapv #(case %
             :user "user"
             :assistant "assistant")
          (if (sequential? roles) roles [roles]))))

(defn base64-encode [c]
  (cond
    ;; already encoded?
    (string? c) c
    (bytes? c) (.encodeToString (Base64/getEncoder) c)
    :else (let [out (ByteArrayOutputStream.)]
            (io/copy c out)
            (base64-encode (.toByteArray out)))))

(defn proto->resource [base-uri o]
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
        (throw (ex-info (format "Cannot convert %s to a resource" (class o)) {}))))))

;; data mime-type priority audience text resource
(defn proto->content [o]
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

(defn ->content-vector [o]
  (cond
    (satisfies? p/ToolErrorResponse o) (->content-vector (p/-err-contents o))
    (sequential? o) (mapv proto->content o)
    :else (->content-vector [o])))

(defn proto->message [o]
  (let [role (when (satisfies? p/Message o) (p/-msg-role o))
        content (if (satisfies? p/Message o) (p/-msg-content o) o)]
    (?assoc {:content (proto->content content)}
            :role (first (to-role-list role)))))

(defn wrap-check-init [handler]
  (fn check-params [rpc-session params]
    (if (::mcp/initialized? @rpc-session)
      (handler rpc-session params)
      (c/invalid-params "Session not initialized."))))

(defn- list-roots-req [rpc-session]
  (.thenApply
    ^CompletableFuture (rpc/send-request rpc-session "roots/list" nil)
    (fn [{:keys [roots]}]
      (if (map? roots) [roots] (vec roots)))))

(def handle-changed-root
  (wrap-check-init
    (fn [rpc-session _]
      (when-let [cb (-> @rpc-session ::mcp/handlers :roots-changed-callback)]
        (cb (create-req-session rpc-session)))
      (swap! rpc-session dissoc ::mcp/roots))))

(defn list-roots [rpc-session]
  (let [{::mcp/keys [client-capabilities]} @rpc-session]
    (if (:roots client-capabilities)
      (if (-> client-capabilities :roots :listChanged)
        ;; use a delay to not start a request immediately (what if swap fails and repeats?)
        (let [state (swap! rpc-session update ::mcp/roots #(or % (delay (list-roots-req rpc-session))))]
          @(::mcp/roots state))
        ;; client will not notify us of changes, so we will always request the roots
        (list-roots-req rpc-session))
      (CompletableFuture/completedFuture []))))

(defn do-sampling [rpc-session {:keys [messages model-preferences system-prompt max-tokens]}]
  (let [{:keys [hints intelligence-priority speed-priority]} model-preferences]
    (rpc/send-request rpc-session
                      "sampling/createMessage"
                      (?assoc {:messages (mapv proto->message (if (sequential? messages) messages [messages]))}
                              :modelPreferences (?assoc nil
                                                        :hints hints
                                                        :intelligencePriority intelligence-priority
                                                        :speedPriority speed-priority)
                              :systemPrompt system-prompt
                              :maxTokens max-tokens))))

(defn change-watcher [k rpc-session o n]
  (let [old-handlers (::mcp/handlers o)
        new-handlers (::mcp/handlers n)
        initialized? (::mcp/initialized? o)]
    (when (and initialized? (not= (:tools old-handlers) (:tools new-handlers)))
      (rpc/send-notification rpc-session "notifications/tools/list_changed" nil))
    (when (and initialized? (not= (:prompts old-handlers) (:prompts new-handlers)))
      (rpc/send-notification rpc-session "notifications/prompts/list_changed" nil))
    (when (and (::mcp/os o) (not (identical? (::mcp/os o) (::mcp/os n))))
      (try
        (.close ^OutputStream (::mcp/os o))
        (catch IOException e
          (log/info e))))))

(defn create-req-session [rpc-session]
  (reify c/RequestExchange
    (client-spec [this]
      (let [{::mcp/keys [client-info client-capabilities]} @rpc-session]
        {:info client-info :capabilities client-capabilities}))
    (get-session [this] rpc-session)
    (log-msg [this level logger msg data]
      (h.logging/do-log rpc-session level logger msg data))
    (list-roots [this] (list-roots rpc-session))
    (sampling [this req] (when (-> @rpc-session ::mcp/client-capabilities :sampling)
                           (do-sampling rpc-session req)))))
