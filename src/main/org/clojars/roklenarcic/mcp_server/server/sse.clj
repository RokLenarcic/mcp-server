(ns org.clojars.roklenarcic.mcp-server.server.sse
  "Server-Sent Events streaming primitives for the MCP Streamable HTTP
  transport.

  Each session has at most one active SSE stream (registered as ::mcp/os),
  a bounded outbound queue (::mcp/q) of messages produced while no stream
  was attached, and a bounded replay buffer (::mcp/replay-buffer) of events
  whose write was either confirmed successful or attempted-but-unconfirmed
  (the stream's OutputStream threw mid-batch or on flush). Both groups are
  eligible for replay on reconnect via `Last-Event-ID`.

  Every server-to-client SSE message is assigned a monotonic event id from
  ::mcp/sse-next-event-id (an AtomicLong) and emitted as

      id: <event-id>
      data: <json>

  When a GET /mcp request arrives with a `Last-Event-ID` header, the
  matching events in the replay buffer (event-id strictly greater than the
  last-event-id) are written out before the pending queue is drained, so
  the client can resume after a transport-level disconnect.

  Delivery semantics on write failure:
  - Events that we never attempted to write (never reached the OutputStream
    inside the failing batch) are re-queued at the head of the outbound
    queue for the next attached stream: at-least-once.
  - Events whose write was attempted but the stream failed during or after
    them (so we cannot tell whether the client received them) are appended
    to the replay buffer only: at-most-once, unless the client reconnects
    with `Last-Event-ID` and the events are still buffered.

  Concurrency:
  - Event id assignment and the choice between live-write and queueing are
    performed under ::mcp/send-mutex so concurrent producers cannot
    interleave or reorder events.
  - The same mutex is held while a GET attaches its stream and replays /
    drains, so live producers see a consistent stream state.

  Buffer eviction:
  - Enqueueing a new message at the tail drops the oldest entry (head)
    when the queue is full, favoring recency.
  - Re-queueing not-yet-attempted messages at the head drops the newest
    entry (tail) when the queue is full, preserving older retried items.
  - The replay buffer drops the oldest entry when full so the most recent
    sends remain available for resumption."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [ring.core.protocols :as ring])
  (:import (java.io IOException OutputStream)
           (java.nio.charset StandardCharsets)
           (java.util ArrayList)
           (java.util.concurrent LinkedBlockingDeque)
           (java.util.concurrent.atomic AtomicLong)))

(def streaming-headers
  "Ring headers for an SSE response."
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "Transfer-Encoding" "chunked"})

(defn cleanup-buffer
  "Drops events older than `expire-ms` from the head of the buffer.

  The buffer is a FIFO of `[event-id timestamp message]` tuples; this
  prevents memory growth when a client never reconnects."
  [^LinkedBlockingDeque buf expire-ms]
  (let [iter (.iterator buf)
        cut-off (- (System/currentTimeMillis) expire-ms)]
    (loop []
      (when (and (.hasNext iter) (< (second (.next iter)) cut-off))
        (.remove iter)
        (recur)))))

(defn- offer-last-drop-oldest
  "Offers `item` at the tail. If the buffer is at capacity, removes the head
  (oldest) to make room first."
  [^LinkedBlockingDeque q item]
  (when-not (.offerLast q item)
    (.pollFirst q)
    (.offerLast q item)))

(defn- offer-first-drop-newest
  "Offers `item` at the head. If the buffer is at capacity, removes the tail
  (newest) to make room first."
  [^LinkedBlockingDeque q item]
  (when-not (.offerFirst q item)
    (.pollLast q)
    (.offerFirst q item)))

(defn close-os
  "Detaches `os` from the session if it is still the registered stream."
  [session os]
  (swap! session update ::mcp/os #(if (= os %) nil %)))

(defn- write-event
  "Writes a single SSE frame to `os` as UTF-8 bytes in a single
  `OutputStream.write(byte[])` call.

  Writing bytes directly (rather than through a buffered Writer) means
  every event hits the OutputStream on its own write call, so a mid-batch
  IOException surfaces immediately and the partition between attempted
  and not-yet-attempted events in `write-responses` is exact."
  [^OutputStream os [event-id _ msg]]
  (.write os (.getBytes (str "id: " event-id "\ndata: " msg "\n\n")
                        StandardCharsets/UTF_8)))

(defn write-responses
  "Drains `q`, appends `record` when supplied, and writes the resulting
  batch to the SSE stream in a single try block.

  `record` is a `[event-id timestamp message]` tuple, or nil when the
  caller only wants to drain the queue.

  On success the batch is appended to the session's replay buffer in
  order, so the events become available to a future reconnect that
  presents `Last-Event-ID`.

  On IOException the stream is detached via `close-os` and the batch is
  partitioned into:
  - attempted events (those whose `write-event` call was begun, including
    the one that threw, plus everything earlier in the batch) — appended
    to the replay buffer only. We cannot tell whether the underlying
    transport delivered them, so we treat them as at-most-once and rely on
    a client reconnect with `Last-Event-ID` to recover any lost ones.
  - not-yet-attempted events (those past the failure point) — pushed back
    onto the head of the queue in original order for the next attached
    stream. The in-flight client-request layer is keyed by id and is
    idempotent under duplicates, so at-least-once redelivery is safe.

  When the queue cannot fit a re-queued entry (bounded capacity), the
  newest entry at the tail is dropped to keep room for the older retried
  item."
  [session ^OutputStream os ^LinkedBlockingDeque q record]
  (let [batch (ArrayList.)
        ^LinkedBlockingDeque replay (::mcp/replay-buffer @session)
        attempted (volatile! 0)]
    (.drainTo q batch)
    (when record
      (.add batch record))
    (log/debug "SSE write - batch size:" (.size batch))
    (locking os
      (try
        (run! (fn [evt]
                (vswap! attempted inc)
                (write-event os evt))
              batch)
        (.flush os)
        ;; Success: append to the replay buffer so the events can be
        ;; resent on a reconnect that supplies Last-Event-ID.
        (when replay
          (run! #(offer-last-drop-oldest replay %) batch))
        (catch IOException e
          (log/debug e "SSE stream write or flush failed; closing")
          (close-os session os)
          (let [n @attempted
                size (.size batch)
                ambiguous (.subList batch 0 n)
                unsent (.subList batch n size)]
            ;; Attempted events go to the replay buffer only. We don't
            ;; know whether the transport delivered them; reconnect via
            ;; Last-Event-ID is the recovery path.
            (when replay
              (run! #(offer-last-drop-oldest replay %) ambiguous))
            ;; Not-yet-attempted events go back to the head of the queue
            ;; in original order so the next attached stream delivers
            ;; them first.
            (run! #(offer-first-drop-newest q %) (reverse unsent))))))
    nil))

(defn replay-events
  "Writes events from the session's replay buffer with event-id strictly
  greater than `last-event-id` to `os`, in event-id order.

  On IOException the stream is detached via `close-os`. The replay buffer
  is left intact so subsequent reconnects can attempt the same replay."
  [session ^OutputStream os last-event-id]
  (let [^LinkedBlockingDeque replay (::mcp/replay-buffer @session)]
    (when (and replay last-event-id)
      (let [events (->> (iterator-seq (.iterator replay))
                        (filter (fn [[eid _ _]] (> eid last-event-id))))]
        (when (seq events)
          (log/debug "SSE replay - replaying events:" (count events))
          (locking os
            (try
              (run! #(write-event os %) events)
              (.flush os)
              (catch IOException e
                (log/debug e "SSE replay write or flush failed; closing")
                (close-os session os)))))))
    nil))

(defn send-to-client-fn
  "Returns a function that sends `msg` to the client.

  Under ::mcp/send-mutex the function allocates a monotonically increasing
  event id from ::mcp/sse-next-event-id, builds an event record, and
  either writes it directly to the active stream or enqueues it. Holding
  the mutex guarantees that event ids and queue/stream order remain
  consistent across concurrent producers and across a concurrent GET that
  is attaching its stream."
  [session]
  (fn [msg]
    (when msg
      (let [{::mcp/keys [^LinkedBlockingDeque q
                         ^AtomicLong sse-next-event-id
                         ^LinkedBlockingDeque replay-buffer
                         send-mutex
                         timeout-ms]} @session]
        (locking send-mutex
          (when timeout-ms
            (cleanup-buffer q timeout-ms)
            (when replay-buffer
              (cleanup-buffer replay-buffer timeout-ms)))
          (let [event-id (if sse-next-event-id
                           (.incrementAndGet sse-next-event-id)
                           0)
                record [event-id (System/currentTimeMillis) msg]
                os (::mcp/os @session)]
            (if os
              (do
                (log/trace "Active SSE stream; writing immediately")
                (write-responses session os q record))
              (do
                (log/trace "No active SSE stream; queuing message")
                (offer-last-drop-oldest q record)))))))))

(defn get-resp
  "Streamable response body for GET /mcp.

  Registers the response output stream as the session's SSE channel,
  optionally replays events whose event-id is greater than `last-event-id`
  from the session's replay buffer, and immediately drains any queued
  messages so the client receives them in order.

  In synchronous Ring mode the stream is detached on return because the
  adapter will close it; in asynchronous mode the stream remains
  registered until a write fails. The whole attach/replay/drain sequence
  runs under ::mcp/send-mutex so concurrent producers cannot interleave
  with the initial replay+drain."
  [session sync? last-event-id]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [_ _ os]
      ;; Flush so the client sees the 200 + headers immediately.
      (.flush ^OutputStream os)
      (locking (::mcp/send-mutex @session)
        (swap! session assoc ::mcp/os os)
        (let [{::mcp/keys [^LinkedBlockingDeque q
                           ^LinkedBlockingDeque replay-buffer
                           timeout-ms]} @session]
          (when timeout-ms
            (cleanup-buffer q timeout-ms)
            (when replay-buffer
              (cleanup-buffer replay-buffer timeout-ms)))
          (when last-event-id
            (replay-events session os last-event-id))
          ;; Only drain the pending queue if the replay write didn't
          ;; detach the stream.
          (when (= os (::mcp/os @session))
            (write-responses session os q nil)))
        (when sync?
          (close-os session os))))))
