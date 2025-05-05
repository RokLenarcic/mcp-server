(ns org.clojars.roklenarcic.mcp-server.protocol
  (:refer-clojure :exclude [read-string]))

(defprotocol ResourceResponse
  (-res-body [this])
  (-res-mime [this])
  (-res-uri [this]))

(defprotocol Content
  (-con-priority [this])
  (-con-audience [this]))

(defprotocol TextContent
  (-con-text [this]))

(defprotocol ImageContent
  (-con-mime-type [this])
  (-con-data [this]))

(defprotocol AudioContent
  (-aud-mime-type [this])
  (-aud-data [this]))

(defprotocol PromptResponse
  (-prompt-desc [this])
  (-prompt-msgs [this]))

(defprotocol Message
  (-msg-role [this])
  (-msg-content [this]))

(defprotocol ToolErrorResponse
  (-err-contents [this]))
