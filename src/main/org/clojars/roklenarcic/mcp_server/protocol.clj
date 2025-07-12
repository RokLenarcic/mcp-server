(ns org.clojars.roklenarcic.mcp-server.protocol
  "This namespace defines the core protocols used throughout the MCP server
   implementation. These protocols define the contracts for different types
   of content and responses in the MCP system."
  (:refer-clojure :exclude [read-string]))

(defprotocol ResourceResponse
  "Protocol for objects that can be returned as resource content."
  (-res-body [this] "Returns the resource body content (String, byte array, or InputStream).")
  (-res-mime [this] "Returns the MIME type of the resource content.")
  (-res-uri [this] "Returns the URI of the resource, or nil if not specified."))

(defprotocol Content
  "Base protocol for all content types in MCP messages."
  (-con-priority [this] "Returns the priority of this content (double), or nil if not specified.")
  (-con-audience [this] "Returns the audience for this content (vector of :user/:assistant), or nil if not specified."))

(defprotocol TextContent
  "Protocol for text-based content in MCP messages."
  (-con-text [this] "Returns the text content as a string."))

(defprotocol ImageContent
  "Protocol for image-based content in MCP messages."
  (-con-mime-type [this] "Returns the MIME type of the image (e.g., 'image/png', 'image/jpeg').")
  (-con-data [this] "Returns the image data as byte array or InputStream."))

(defprotocol AudioContent
  "Protocol for audio-based content in MCP messages."
  (-aud-mime-type [this] "Returns the MIME type of the audio (e.g., 'audio/wav', 'audio/mp3').")
  (-aud-data [this] "Returns the audio data as byte array or InputStream."))

(defprotocol PromptResponse
  "Protocol for responses to prompt requests."
  (-prompt-desc [this] "Returns the description of the prompt response.")
  (-prompt-msgs [this] "Returns the messages in the prompt response (vector of Message objects)."))

(defprotocol Message
  "Protocol for messages in MCP conversations."
  (-msg-role [this] "Returns the role of the message sender (:user or :assistant).")
  (-msg-content [this] "Returns the content of the message (Content protocol object)."))

(defprotocol ToolErrorResponse
  "Protocol for tool error responses."
  (-err-contents [this] "Returns the error content (one or more Content objects describing the error)."))
