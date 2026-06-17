(ns org.clojars.roklenarcic.mcp-server.server.sse
  "Server-Sent Events streaming primitives for the MCP Streamable HTTP
  transport.

  A session has at most one active SSE stream (registered as ::mcp/os) plus a
  bounded queue (::mcp/q, a LinkedBlockingDeque) of messages produced while no
  stream was attached. When a GET /mcp arrives the queue is drained onto the
  new stream; subsequent server-to-client messages are written immediately.

  Because the queue is bounded:
  - enqueueing a new message at the tail drops the oldest entry (head) when
    the queue is full, favoring recency;
  - re-queueing failed-write messages at the head drops the newest entry
    (tail) when the queue is full, preserving older retried items."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [ring.core.protocols :as ring])
  (:import (java.io IOException OutputStream)
           (java.util ArrayList)
           (java.util.concurrent LinkedBlockingDeque)))

(def streaming-headers
  "Ring headers for an SSE response."
  {"Content-Type" "text/event-stream"
   "Transfer-Encoding" "chunked"})

(defn cleanup-buffer
  "Drops messages older than `expire-ms` from the head of the queue.

  The queue is a FIFO of `[timestamp message]` tuples; this prevents memory
  growth when a client never reconnects."
  [^LinkedBlockingDeque q expire-ms]
  (let [iter (.iterator q)
        cut-off (- (System/currentTimeMillis) expire-ms)]
    (loop []
      (when (and (.hasNext iter) (< (first (.next iter)) cut-off))
        (.remove iter)
        (recur)))))

(defn- offer-last-drop-oldest
  "Offers `item` at the tail. If the queue is at capacity, removes the head
  (oldest) to make room first."
  [^LinkedBlockingDeque q item]
  (when-not (.offerLast q item)
    (.pollFirst q)
    (.offerLast q item)))

(defn- offer-first-drop-newest
  "Offers `item` at the head. If the queue is at capacity, removes the tail
  (newest) to make room first."
  [^LinkedBlockingDeque q item]
  (when-not (.offerFirst q item)
    (.pollLast q)
    (.offerFirst q item)))

(defn close-os
  "Detaches `os` from the session if it is still the registered stream."
  [session os]
  (swap! session update ::mcp/os #(if (= os %) nil %)))

(defn write-responses
  "Drains the queue, appends `msg` when supplied, and writes the resulting
  batch to the SSE stream in a single try block.

  On IOException (from any write or the final flush) the stream is detached
  via `close-os` and every batched message is pushed back onto the head of
  the queue in original order, so the next attached stream can deliver them.
  Re-delivery is at-least-once; the in-flight client-request layer is keyed
  by id and is idempotent under duplicates.

  When the queue cannot fit a re-queued entry (bounded capacity), the newest
  entry at the tail is dropped to keep room for the older retried item."
  [session ^OutputStream os ^LinkedBlockingDeque q msg]
  (let [w (io/writer os)
        batch (ArrayList.)]
    (.drainTo q batch)
    (when msg
      (.add batch [(System/currentTimeMillis) msg]))
    (log/debug "SSE write - batch size:" (.size batch))
    (locking os
      (try
        (run! #(.write w (str "data: " (second %) "\n\n")) batch)
        (.flush w)
        (catch IOException e
          (log/debug e "SSE stream write or flush failed; closing and re-queuing")
          (close-os session os)
          ;; Re-queue at the head in reverse order so the original order is
          ;; restored at the front of the deque.
          (run! #(offer-first-drop-newest q %) (reverse batch)))))
    nil))

(defn send-to-client-fn
  "Returns a function that sends `msg` to the client.

  If a stream is attached the message is written immediately; otherwise it is
  queued (oldest dropped if the queue is at capacity) until the next GET /mcp
  connects."
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
          (offer-last-drop-oldest q [(System/currentTimeMillis) msg]))))))

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
