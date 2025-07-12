(ns org.clojars.roklenarcic.mcp-server.server.sse
  "This namespace handles Server-Sent Events (SSE) communication for the MCP server.
  It provides streaming capabilities for real-time communication between server
  and client over HTTP, managing output streams, message queuing, and connection cleanup."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [ring.core.protocols :as ring])
  (:import (java.io IOException OutputStream)
           (java.util.concurrent ConcurrentLinkedQueue)))

(def streaming-headers {"Content-Type" "text/event-stream"
                        "Transfer-Encoding" "chunked"})

(defn cleanup-buffer
  "Removes expired messages from the message queue.
   
   Parameters:
   - q: ConcurrentLinkedQueue containing [timestamp, message] tuples
   - expire-ms: maximum age in milliseconds before messages are considered expired
   
   This function helps prevent memory leaks by removing old messages that
   couldn't be delivered due to connection issues."
  [^ConcurrentLinkedQueue q expire-ms]
  (let [iter (.iterator q)
        cut-off (- (System/currentTimeMillis) expire-ms)]
    (loop []
      (when (and (.hasNext iter) (< (first (.next iter)) cut-off))
        (.remove iter)
        (recur)))))

(defn close-os
  "Safely closes an output stream and removes it from the session.

   Parameters:
   - session: the session atom
   - os: output stream to close and remove

   This function ensures that the output stream reference is removed from
   the session only if it matches the current stream, preventing race conditions."
  [session os]
  (swap! session update ::mcp/os #(if (= os %) nil %)))

(defn write-responses
  "Writes queued responses and a new message to the SSE stream.
   
   Parameters:
   - session: the session atom
   - os: output stream to write to
   - q: message queue containing pending messages
   - msg: new message to write (optional)
   
   This function handles:
   - Writing all queued messages to the stream
   - Writing the new message if provided
   - Error handling and stream cleanup on write failures
   - Re-queuing messages that couldn't be written"
  [session ^OutputStream os ^ConcurrentLinkedQueue q msg]
  (let [w (io/writer os)]
    (log/debug "Writing responses to SSE stream - queued messages:" (.size q) "new message:" (some? msg))
    (locking os
      (try
        (loop []
          (when-let [it (.poll q)]
            (try
              (.write w (str "data: " (second it) "\n\n"))
              (catch IOException e
                (log/debug "Failed to write queued message, re-queuing")
                (.offer q it)
                (throw e)))
            (recur)))
        
        ;; Write the new message if provided
        (when msg 
          (log/debug "Writing new message to SSE stream")
          (.write w (str "data: " msg "\n\n")))
        
        (catch IOException e
          (log/debug e "SSE stream write failed, closing connection")
          (close-os session os)
          ;; Re-queue the new message if it couldn't be written
          (when msg 
            (log/debug "Re-queuing message due to write failure")
            (.offer q [(System/currentTimeMillis) msg]))))
      (.flush w)
      nil)))

(defn send-to-client-fn
  "Creates a function for sending messages to the client via SSE.
   
   Parameters:
   - session: the session atom
   
   Returns a function that can be used to send messages to the client.

   The returned function will either write directly to an active stream
   or queue the message for later delivery."
  [session]
  (fn [msg]
    (let [{::mcp/keys [q os timeout-ms]} @session]
      (cleanup-buffer q timeout-ms)
      (if os
        (do
          (log/debug "Active SSE stream found, writing message immediately")
          (write-responses session os q msg))
        (when msg 
          (log/debug "No active SSE stream, queuing message")
          (.offer ^ConcurrentLinkedQueue q [(System/currentTimeMillis) msg]))))))

(defn get-resp 
  "Creates a streamable response body for GET requests (SSE connection establishment).
   
   Parameters:
   - session: the session atom
   - sync?: whether to close the stream immediately after writing
   - endpoint: optional endpoint information to send as first message
   
   Returns a StreamableResponseBody that establishes the SSE connection."
  [session sync? endpoint]
  (log/debug "Creating GET response for SSE connection - sync:" sync? "endpoint:" endpoint)
  (reify ring/StreamableResponseBody
    (write-body-to-stream [this response os]
      (when endpoint
        (.write (io/writer os) (format "event: endpoint\ndata: %s\n\n" endpoint)))
      (.flush ^OutputStream os)
      (swap! session assoc ::mcp/os os)
      (when sync? (close-os session os)))))

(defn post-resp 
  "Creates a streamable response body for POST requests (message sending).
   
   Parameters:
   - session: the session atom
   - resp: response message to send
   - sync?: whether to close the stream after sending
   
   Returns a StreamableResponseBody that sends the response via SSE."
  [session resp sync?]
  (log/debug "Creating POST response for SSE message - sync:" sync?)
  (reify ring/StreamableResponseBody
    (write-body-to-stream [this response os]
      (log/debug "Handling POST response via SSE")
      (let [new-sess (swap! session
                            (fn [session]
                              (if (::mcp/os session) 
                                (do
                                  (log/debug "Using existing SSE output stream")
                                  session)
                                (do
                                  (log/debug "Establishing new SSE output stream for POST")
                                  (assoc session ::mcp/os os)))))]
        
        (log/debug "Processing SSE message response")
        (write-responses session os (::mcp/q new-sess) resp)
        
        ;; Close stream if synchronous or if another stream is active
        (when (or sync? (not= (::mcp/os new-sess) os))
          (log/debug "Closing SSE stream - sync:" sync? "different stream:" (not= (::mcp/os new-sess) os))
          (close-os session os)
          (.close ^OutputStream os))))))
