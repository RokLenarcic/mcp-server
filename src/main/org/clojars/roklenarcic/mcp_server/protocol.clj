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

(defprotocol ResourceLinkContent
  "Protocol for resource link content in MCP messages.

   Unlike embedded resource content (which carries the body), a resource link
   is a lightweight pointer to a resource the client may fetch separately.
   Introduced in MCP protocol 2025-06-18."
  (-link-uri [this] "Returns the resource URI (string).")
  (-link-name [this] "Returns the programmatic name of the resource (string).")
  (-link-title [this] "Returns the optional human-readable title, or nil.")
  (-link-description [this] "Returns the optional description, or nil.")
  (-link-mime-type [this] "Returns the optional MIME type, or nil."))

(defprotocol PromptResponse
  "Protocol for responses to prompt requests."
  (-prompt-desc [this] "Returns the description of the prompt response.")
  (-prompt-msgs [this] "Returns the messages in the prompt response (vector of Message objects).")
  (-prompt-meta [this] "Returns the optional :_meta map to attach to the prompt result envelope, or nil."))

(defprotocol Message
  "Protocol for messages in MCP conversations."
  (-msg-role [this] "Returns the role of the message sender (:user or :assistant).")
  (-msg-content [this] "Returns the content of the message (Content protocol object)."))

(defprotocol ToolErrorResponse
  "Protocol for tool error responses."
  (-err-contents [this] "Returns the error content (one or more Content objects describing the error)."))

(defprotocol ToolResult
  "Protocol for tool results that include structured content (MCP 2025-06-18).

   Used when a tool's :output-schema is defined and the handler wants to
   return both displayable content and structured data conforming to the
   schema. Created via core/tool-result."
  (-result-content [this] "Returns the displayable content (Content protocol object(s) or collection).")
  (-result-structured [this] "Returns the structured content map matching the tool's :output-schema.")
  (-result-meta [this] "Returns the optional :_meta map to attach to the tool result envelope, or nil."))

(defprotocol ResourceReadResult
  "Protocol for resources/read result envelopes that carry :_meta
   (MCP 2025-06-18).

   Use this only when you need to attach :_meta to a resources/read
   response. For the common case, return a ResourceResponse (or a
   collection of them) directly. Created via core/resource-read-result."
  (-read-contents [this] "Returns the contents (one ResourceResponse or a collection of them).")
  (-read-meta [this] "Returns the :_meta map to attach to the read-resource result envelope."))
