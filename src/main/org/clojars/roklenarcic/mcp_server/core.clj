(ns org.clojars.roklenarcic.mcp-server.core
  (:require [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as rpc]
            [org.clojars.roklenarcic.mcp-server.util :refer [map-of]])
  (:import (java.io InputStream)))

(defprotocol RequestExchange
  "An Exchange scoped to the request."
  (client-spec [this] "Data about client capabilities.")
  (get-session [this] "Returns session data atom")
  (log-msg [this level logger msg data]
    "Logs to client, level is one of
    :debug, :info, :notice, :warning, :error, :critical, :alert, :emergency,

    - logger is a string")
  (list-roots [this] "List of roots from the client, returns CompletableFuture.")
  (sampling [this sampling-request]
    "Request a sampling, returns CompletableFuture or nil if client doesn't support sampling."))

(extend-protocol p/ResourceResponse
  String
  (-res-body [this] this)
  (-res-mime [this] "text/plain")
  (-res-uri [this] nil)
  InputStream
  (-res-body [this] this)
  (-res-mime [this] "application/octet-stream")
  (-res-uri [this] nil))

(extend-type (class (make-array Byte/TYPE 0)) p/ResourceResponse
  (-res-body [this] this)
  (-res-mime [this] "application/octet-stream")
  (-res-uri [this] nil))

(defrecord JSONRPCError [code message data])

(defn invalid-request
  "Create an invalid request error response"
  ([data] (invalid-request data "Invalid Request"))
  ([data message]
   (->JSONRPCError rpc/INVALID_REQUEST message data)))

(defn invalid-params
  "Create an invalid params error response"
  ([data] (invalid-params data "Invalid Params"))
  ([data message]
   (->JSONRPCError rpc/INVALID_PARAMS message data)))

(defn internal-error
  "Create an invalid request error response"
  [data message]
  (->JSONRPCError rpc/INTERNAL_ERROR message data))

(defn resource-not-found
  "Creates a resource not found error message"
  [uri]
  (->JSONRPCError rpc/RESOURCE_NOT_FOUND "Resource Not Found" uri))

(defn resource
  "Create a resource content for Get Resource response. If URI is not specified, the
  request URI will be used.

  If body is a String, a text resource will be generated, but if byte array or
  InputStream, then a Blob resource is created."
  [body mime-type uri]
  (reify p/ResourceResponse
    (-res-body [this] (p/-res-body body))
    (-res-mime [this] (or mime-type (p/-res-mime body)))
    (-res-uri [this] uri)))

(defn resource-desc
  "Create a resource description for List Resources operation."
  [uri name description mime-type annotations]
  (map-of uri name description mime-type annotations))

(defn audio-content
  "Audio Content for Tools and Prompts.

  Data can be byte array or input stream, priority is a double,
  audience is vector of :user, :assistant"
  ([data mime-type]
   (audio-content data mime-type nil nil))
  ([data mime-type priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/AudioContent
     (-aud-mime-type [this] mime-type)
     (-aud-data [this] data))))

(defn image-content
  "Image Content for Tools and Prompts.

  Data can be byte array or input stream, priority is a double,
  audience is vector of :user, :assistant"
  ([data mime-type]
   (image-content data mime-type nil nil))
  ([data mime-type priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/ImageContent
     (-con-mime-type [this] mime-type)
     (-con-data [this] data))))

(defn embedded-content
  "Embedded Resource Content for Tools and Prompts.

  resource is ResourceResponse protocol object (String, byte[]
  priority is a double, audience is vector of :user, :assistant"
  ([resource] (embedded-content resource nil nil))
  ([resource priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/ResourceResponse
     (-res-body [this] (p/-res-body resource))
     (-res-mime [this] (p/-res-mime resource))
     (-res-uri [this] (p/-res-uri resource)))))

(defn text-content
  "Embedded Resource Content for Tools and Prompts.

  resource if created by resource function in this namespace,
  priority is a double, audience is vector of :user, :assistant"
  ([text] (text-content text nil nil))
  ([text priority audience]
   (reify
     p/Content
     (-con-priority [this] priority)
     (-con-audience [this] audience)
     p/TextContent
     (-con-text [this] text))))

(defn message
  "Message, role is one of :user, :assistant, content is one of image-content, embedded-content or text-content."
  [role content]
  (reify p/Message
    (-msg-role [this] role)
    (-msg-content [this] content)))

(defn prompt-resp
  "Prompt response: a description and one or more prompt messages. Single messages are automatically
  wrapped into a vector.

  A message is core/Message, or just a core/Content object (which becomes a Message with nil role)."
  [description messages]
  (reify p/PromptResponse
    (-prompt-desc [this] description)
    (-prompt-msgs [this] messages)))

(defn completion-resp
  "Completion response. Protocol limits responses to 100 items.

  - values is a coll of strings
  - total is integer of number of all items, optional
  - has-more? is a boolean indicating if there's more than 100 items

  1-arg arity assumes that the values items is all there is"
  ([values] (completion-resp values (count values) (< 100 (count values))))
  ([values total] (completion-resp values total (when total (< 100 total))))
  ([values total has-more?]
   {:values (take 100 values)
    :total total
    :hasMore has-more?}))

(defn tool-error
  "Tool error response. The content is one content object or a collection of them."
  [content]
  (reify p/ToolErrorResponse
    (-err-contents [this] content)))

(defn model-preferences
  "Model preferences for a sampling request. hints is a vector of {:name: \"claude-3-sonnet\"}"
  [hints intelligence-priority speed-priority]
  (map-of hints intelligence-priority speed-priority))

(defn sampling-request
  "Create a sampling request.

  - messages is one or more core/Message objects or core/Content objects.
  - model-preferences is a map (see model-preferences function)
  - system-prompt is a string
  - max-tokens is a number"
  [messages model-preferences system-prompt max-tokens]
  (map-of messages model-preferences system-prompt max-tokens))