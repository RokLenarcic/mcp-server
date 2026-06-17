(ns org.clojars.roklenarcic.mcp-server.server.sse
  "Server-Sent Events streaming primitives for the MCP Streamable HTTP
  transport.

  A session has at most one active SSE stream (registered as ::mcp/os) plus a
  queue (::mcp/q) of messages produced while no stream was attached. When a
  GET /mcp arrives the queue is drained onto the new stream; subsequent
  server-to-client messages are written immediately."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [ring.core.protocols :as ring])
  (:import (java.io IOException OutputStream)
           (java.util.concurrent ConcurrentLinkedQueue)))

(def streaming-headers
  "Ring headers for an SSE response."
  {"Content-Type" "text/event-stream"
   "Transfer-Encoding" "chunked"})

(defn cleanup-buffer
  "Drops messages older than `expire-ms` from the head of the queue.

  The queue is a FIFO of `[timestamp message]` tuples; this prevents memory
  growth when a client never reconnects."
  [^ConcurrentLinkedQueue q expire-ms]
  (let [iter (.iterator q)
        cut-off (- (System/currentTimeMillis) expire-ms)]
    (loop []
      (when (and (.hasNext iter) (< (first (.next iter)) cut-off))
        (.remove iter)
        (recur)))))

(defn close-os
  "Detaches `os` from the session if it is still the registered stream."
  [session os]
  (swap! session update ::mcp/os #(if (= os %) nil %)))

(defn write-responses
  "Writes any queued messages, then `msg` (when supplied), to the SSE stream.

  On IOException the stream is detached; if `msg` could not be written it is
  re-queued so that the next attached stream can deliver it."
  [session ^OutputStream os ^ConcurrentLinkedQueue q msg]
  (let [w (io/writer os)]
    (log/debug "SSE write - queued:" (.size q) "new?" (some? msg))
    (locking os
      (try
        (loop []
          (when-let [it (.poll q)]
            (try
              (.write w (str "data: " (second it) "\n\n"))
              (catch IOException e
                (log/debug "Failed to write queued message; re-queuing")
                (.offer q it)
                (throw e)))
            (recur)))
        (when msg
          (log/trace "Writing new message to SSE stream")
          (.write w (str "data: " msg "\n\n")))
        (catch IOException e
          (log/debug e "SSE stream write failed; closing")
          (close-os session os)
          (when msg
            (.offer q [(System/currentTimeMillis) msg]))))
      (.flush w)
      nil)))

(defn send-to-client-fn
  "Returns a function that sends `msg` to the client.

  If a stream is attached the message is written immediately; otherwise it is
  queued until the next GET /mcp connects."
  [session]
  (fn [msg]
    (let [{::mcp/keys [q os timeout-ms]} @session]
      (cleanup-buffer q timeout-ms)
      (if os
        (do
          (log/trace "Active SSE stream; writing immediately")
          (write-responses session os q msg))
        (when msg
          (log/trace "No active SSE stream; queuing message")
          (.offer ^ConcurrentLinkedQueue q [(System/currentTimeMillis) msg]))))))

(defn get-resp
  "Streamable response body for GET /mcp.

  Registers the response output stream as the session's SSE channel so that
  server-originated messages can flow to the client, and immediately drains
  any queued messages. In synchronous Ring mode the stream is detached on
  return because the adapter will close it; in asynchronous mode the stream
  remains registered until a write fails."
  [session sync?]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [_ _ os]
      ;; Flush so the client sees the 200 + headers immediately.
      (.flush ^OutputStream os)
      (swap! session assoc ::mcp/os os)
      ;; Drain any messages produced while no stream was attached.
      (write-responses session os (::mcp/q @session) nil)
      (when sync?
        (close-os session os)))))
