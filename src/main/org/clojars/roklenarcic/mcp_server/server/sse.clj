(ns org.clojars.roklenarcic.mcp-server.server.sse
  "Server-Sent Events streaming primitives for the MCP Streamable HTTP
  transport.

  Each session has at most one active GET-SSE stream, a bounded outbound
  queue (::mcp/q in the session atom) of messages produced while no stream
  was attached, and a bounded replay buffer of events whose write was either
  confirmed successful or attempted-but-unconfirmed. Both groups are eligible
  for replay on reconnect via `Last-Event-ID`.

  Transport state that does not need to survive a session freeze is kept on
  the `StreamableSession` record rather than in the session atom:
  - `send-mutex`        — serialises SSE writes
  - `sse-next-event-id` — monotonic event id counter (AtomicLong)
  - `replay-buffer`     — LinkedBlockingDeque for Last-Event-ID reconnects
  - `os`                — atom holding the active GET-SSE OutputStream (or nil)
  - `post-streams`      — atom holding the set of in-flight POST-response OSes

  The session atom retains the user-visible, persistable state:
  `::mcp/q` (pending outbound messages), `::mcp/id`, `::mcp/session-creation-time`,
  `::mcp/timeout-ms`, and all core protocol keys.

  Connection model — single GET per session, plus request-scoped POST streams:
  - A session holds at most one GET-SSE stream at a time. When a new GET
    /mcp arrives, `get-resp` calls `set-os!` which atomically replaces the
    registered stream and closes the previous one. This is by design, not
    a limitation.
  - We deliberately do not maintain a list of live GET streams and fan out
    to all (clients would receive duplicates) or pick one (clients cannot
    predict which stream receives which message). Multiple concurrent
    streams to the same session have no coherent delivery semantics.
  - Resilience to dropped GET connections is provided by the replay buffer
    and `Last-Event-ID`: when a write fails the stream is detached and the
    client's next GET resumes from where it left off, with attempted-but-
    unconfirmed events already in the replay buffer for the resumption.
  - Each in-flight POST that returns SSE is registered in the `post-streams`
    atom for the duration of its handler dispatch and bound (per thread) to
    the dynamic var `*post-stream*` so that server-initiated messages
    produced inside the handler scope can be routed deterministically back
    to the originating request's response stream. Notifications produced by
    other concurrent POSTs or by code without the binding fall through to
    the GET stream first and only iterate over the registered POST streams
    as a fallback. POST streams skip the replay buffer and never drain the
    outbound queue: they are ephemeral, last only for the duration of the
    originating request, and have no `Last-Event-ID` resumption semantics.

  Routing priority in `send-to-client-fn`:
  1. `*post-stream*` — when bound, the OutputStream of the in-flight POST
     currently dispatching on this thread. Honoring the binding first
     keeps a handler's notifications correlated with the response of the
     same client request, per the Streamable HTTP spec's preference for
     related server messages on the POST stream.
  2. The active GET-SSE stream — written live through `write-responses`,
     populates the replay buffer. Used when no `*post-stream*` is bound
     (the typical case for an unrelated server-initiated message) or when
     the bound stream's write failed.
  3. Other in-flight POST-response streams (excluding `*post-stream*`,
     which we already attempted). Iterated with `some` +
     `write-to-post-stream` so a stream that has just failed
     self-deregisters and the next one is tried. Ephemeral write, no
     replay buffer append, no queue drain.
  4. `::mcp/q` — the bounded outbound queue, drained on the next GET attach.

  Binding propagation: `*post-stream*` is set by the POST transport
  inside `write-body-to-stream`, so any synchronous code reached from the
  handler (including the `change-watcher` notifications fired during
  dispatch) sees the bound value. Asynchronous handlers that return a
  CompletableFuture must explicitly carry the binding into their
  background work via `clojure.core/bound-fn` (or an equivalent capture)
  if they want notifications produced from background threads to keep
  routing to the originating POST stream. Without that capture the
  binding has unwound by the time the future runs and notifications fall
  through to the GET stream / fallback iteration / queue.

  Every server-to-client SSE message is assigned a monotonic event id from
  `sse-next-event-id` (an AtomicLong on the record) and emitted as

      id: <event-id>
      data: <json>

  The same counter is shared by GET-SSE and POST-SSE streams so event ids
  never collide on the wire.

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
  - A POST-SSE fallback write that fails simply detaches the stream and
    re-queues the event for the next attached stream; nothing is appended
    to the replay buffer because POST streams are not eligible for replay.

  Concurrency:
  - Event id assignment and the choice between live-write and queueing are
    performed under `send-mutex` so concurrent producers cannot interleave
    or reorder events.
  - The same mutex is held while a GET attaches its stream and replays /
    drains, so live producers see a consistent stream state.
  - POST stream registration, deregistration, and the POST response write
    are also serialized under `send-mutex` so a server-initiated fallback
    write cannot interleave with the response write that ends (and
    deregisters) the stream.

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

(def ^:dynamic *post-stream*
  "When bound to an OutputStream of an in-flight POST-SSE response, the
  POST transport tells `send-to-client-fn` to route server-initiated
  messages produced inside the handler dispatch back to that specific
  stream first (see the routing priority in the namespace docstring).

  The binding is established by `post-sse-resp` in the HTTP transport
  before the handler is dispatched and is unwound when the handler
  returns. Synchronous handlers therefore inherit the binding for free.
  Asynchronous handlers that return a CompletableFuture must explicitly
  propagate the binding into their background threads via
  `clojure.core/bound-fn` (or equivalent capture) if they want
  notifications produced from those threads to keep routing to the
  originating POST stream rather than falling through to the GET stream
  / fallback iteration / queue."
  nil)

(defrecord StreamableSession
  [session            ;; atom — core protocol state + id, creation-time, timeout-ms, q, send-to-client
   send-mutex         ;; Object — serialises all SSE writes
   sse-next-event-id  ;; AtomicLong — monotonic event id counter
   replay-buffer      ;; LinkedBlockingDeque — replay buffer for Last-Event-ID reconnects
   os                 ;; atom<OutputStream|nil> — the active GET-SSE stream
   post-streams])     ;; atom<#{OutputStream}> — in-flight POST-response streams

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

(defn set-os!
  "Registers `new-os` as the active GET-SSE stream on the StreamableSession,
  closing the previous stream if one was attached and it differs from `new-os`.

  Must be called under `send-mutex` so producers see a consistent stream state."
  [^StreamableSession ss new-os]
  (let [[old _] (reset-vals! (:os ss) new-os)]
    (when (and old (not (identical? old new-os)))
      (try (.close ^OutputStream old)
           (catch IOException e
             (log/debug e "Error closing old GET-SSE stream"))))))

(defn close-os
  "Detaches `os` from the StreamableSession's GET stream slot if it is
  still the registered stream. Does not attempt to close the OutputStream —
  this is called after a write failure when the stream is already broken."
  [^StreamableSession ss os]
  (swap! (:os ss) #(if (identical? os %) nil %)))

(defn register-post-stream
  "Adds `os` to the StreamableSession's set of in-flight POST-response SSE
  streams.

  Called from the POST-response streaming body before its handler is
  dispatched so that server-initiated messages produced during dispatch
  fall back to this stream when no GET-SSE stream is attached (see the
  routing priority in the namespace docstring).

  The set is mutated under `send-mutex` so the modification is visible
  to a concurrent `send-to-client-fn` consistently with the GET stream
  check it performs in the same critical section."
  [^StreamableSession ss os]
  (locking (:send-mutex ss)
    (swap! (:post-streams ss) conj os)))

(defn deregister-post-stream
  "Removes `os` from the StreamableSession's set of in-flight POST-response
  SSE streams.

  This does NOT close the OutputStream — the Ring adapter owns the
  lifecycle of the POST response body and closes it after
  `write-body-to-stream` returns. Deregistration only stops
  `send-to-client-fn` from routing further messages to this stream.

  Idempotent: `disj` on a missing entry is a no-op, so it is safe to call
  this from a `finally` clause after `write-post-response-and-deregister`
  has already removed the stream as part of an atomic write+deregister."
  [^StreamableSession ss os]
  (locking (:send-mutex ss)
    (swap! (:post-streams ss) disj os)))

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
  [^StreamableSession ss ^OutputStream os ^LinkedBlockingDeque q record]
  (let [batch (ArrayList.)
        ^LinkedBlockingDeque replay (:replay-buffer ss)
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
        (run! #(offer-last-drop-oldest replay %) batch)
        (catch IOException e
          (log/debug e "SSE stream write or flush failed; closing")
          (close-os ss os)
          (let [n @attempted
                size (.size batch)
                ambiguous (.subList batch 0 n)
                unsent (.subList batch n size)]
            ;; Attempted events go to the replay buffer only. We don't
            ;; know whether the transport delivered them; reconnect via
            ;; Last-Event-ID is the recovery path.
            (run! #(offer-last-drop-oldest replay %) ambiguous)
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
  [^StreamableSession ss ^OutputStream os last-event-id]
  (let [^LinkedBlockingDeque replay (:replay-buffer ss)]
    (when last-event-id
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
                (close-os ss os)))))))
    nil))

(defn- write-to-post-stream
  "Writes a single SSE event to a registered POST-response stream and
  flushes.

  POST events skip the replay buffer because POST-SSE streams are
  ephemeral and have no `Last-Event-ID` resumption semantics; the queue is
  not drained either, so older queued messages keep waiting for the next
  GET attach. Only the supplied `record` is written.

  Returns true on success, false if the stream's write or flush threw
  IOException — in which case the stream is removed from `post-streams`
  so subsequent producers do not pick it again. Callers should treat a
  false return as 'event not delivered' and queue the record for the
  next attached stream."
  [^StreamableSession ss ^OutputStream os record]
  (try
    (write-event os record)
    (.flush os)
    true
    (catch IOException e
      (log/debug e "POST-SSE fallback write or flush failed; deregistering")
      (swap! (:post-streams ss) disj os)
      false)))

(defn write-post-response-and-deregister
  "Writes the JSON-RPC response for an in-flight POST as a single SSE
  event on `os` and atomically removes `os` from `post-streams`, both
  under `send-mutex`.

  Combining the two steps under a single mutex acquisition prevents a
  concurrent `send-to-client-fn` from routing one more fallback message
  to `os` after we have written the response — clients should observe
  EOF immediately after the response event, not an unrelated event
  spliced in between.

  `msg` is the already-serialized JSON-RPC response string. When `msg`
  is nil (the handler produced no response — e.g. a cancelled request),
  nothing is written but the stream is still deregistered.

  POST events allocate an event id from `sse-next-event-id` (the same
  counter used by GET-SSE writes, so ids never collide on the wire) but
  are NOT appended to the replay buffer: the POST stream is
  request-scoped and cannot be resumed via `Last-Event-ID`.

  On IOException the failure is logged and swallowed — the request is
  already past handler dispatch and there is no useful place to report a
  failed final write to."
  [^StreamableSession ss ^OutputStream os msg]
  (locking (:send-mutex ss)
    (when msg
      (let [event-id (.incrementAndGet ^AtomicLong (:sse-next-event-id ss))
            record [event-id (System/currentTimeMillis) msg]]
        (try
          (write-event os record)
          (.flush os)
          (catch IOException e
            (log/debug e "POST-SSE response write or flush failed")))))
    (swap! (:post-streams ss) disj os)))

(defn send-to-client-fn
  "Returns a function that sends `msg` to the client.

  Under `send-mutex` the function allocates a monotonically increasing
  event id from `sse-next-event-id`, builds an event record, and routes
  it according to the priority chain documented in the namespace
  docstring:

  1. `*post-stream*` (when bound) — the OutputStream of the in-flight
     POST currently dispatching on this thread. Written ephemerally via
     `write-to-post-stream`; on failure the stream self-deregisters and
     routing falls through to the next tier.
  2. The active GET-SSE stream — written live through `write-responses`,
     which also drains the outbound queue and appends to the replay
     buffer.
  3. Other in-flight POST-response streams (excluding any already-
     attempted `*post-stream*`) — iterated with `some` so a stream whose
     write fails self-deregisters and the next one is tried. The outbound
     queue is NOT drained because POST streams are request-scoped and
     should not absorb older queued messages destined for the next GET.
  4. `::mcp/q` (the bounded outbound queue) — used when no stream
     successfully accepted the event; delivery happens on the next GET
     attach.

  Holding `send-mutex` guarantees that event ids and queue/stream order
  remain consistent across concurrent producers, across a concurrent GET
  that is attaching its stream, and across a concurrent POST stream
  registering or deregistering."
  [^StreamableSession ss]
  (fn [msg]
    (when msg
      (let [{::mcp/keys [^LinkedBlockingDeque q timeout-ms]} @(:session ss)]
        (when timeout-ms
          (cleanup-buffer q timeout-ms)
          (cleanup-buffer (:replay-buffer ss) timeout-ms))
        (locking (:send-mutex ss)
          (let [event-id (.incrementAndGet ^AtomicLong (:sse-next-event-id ss))
                record [event-id (System/currentTimeMillis) msg]
                os @(:os ss)
                post-streams @(:post-streams ss)
                bound *post-stream*
                bound-ok? (when bound (write-to-post-stream ss bound record))]
            (cond
              bound-ok?
              (log/trace "Routed to bound POST-SSE stream (originating request)")

              os
              (do
                (log/trace "Active GET-SSE stream; writing immediately")
                (write-responses ss os q record))

              :else
              (let [other-posts (cond->> post-streams
                                  bound (remove #(identical? bound %)))]
                (if (some #(write-to-post-stream ss % record) other-posts)
                  (log/trace "Routed to a fallback POST-SSE stream")
                  (do
                    (log/trace "No active SSE stream; queuing message")
                    (offer-last-drop-oldest q record)))))))))))

(defn make-streamable-session
  "Wraps an already-populated session atom in a `StreamableSession` record,
  creating the transport-internal infrastructure (mutex, event-id counter,
  replay buffer, stream slots) and wiring the `send-to-client` closure back
  into the atom.

  `session` must already contain the core protocol keys, `::mcp/id`,
  `::mcp/session-creation-time`, `::mcp/q`, and `::mcp/timeout-ms`.
  `replay-cap` is the capacity of the replay buffer."
  [session replay-cap]
  (let [ss (->StreamableSession
             session
             (Object.)
             (AtomicLong. 0)
             (LinkedBlockingDeque. (int replay-cap))
             (atom nil)
             (atom #{}))]
    (swap! session assoc ::mcp/send-to-client (send-to-client-fn ss))
    ss))

(defn get-resp
  "Streamable response body for GET /mcp.

  Registers the response output stream as the session's SSE channel via
  `set-os!` (which closes any previously attached GET stream), optionally
  replays events whose event-id is greater than `last-event-id` from the
  replay buffer, and immediately drains any queued messages so the client
  receives them in order.

  `set-os!` is called inside `send-mutex` so concurrent producers see a
  consistent stream state throughout the attach/replay/drain sequence.

  In synchronous Ring mode the stream is detached on return because the
  adapter will close it; in asynchronous mode the stream remains
  registered until a write fails."
  [^StreamableSession ss sync? last-event-id]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [_ _ os]
      ;; Flush so the client sees the 200 + headers immediately.
      (.flush ^OutputStream os)
      (locking (:send-mutex ss)
        (set-os! ss os)
        (let [{::mcp/keys [^LinkedBlockingDeque q timeout-ms]} @(:session ss)]
          (when timeout-ms
            (cleanup-buffer q timeout-ms)
            (cleanup-buffer (:replay-buffer ss) timeout-ms))
          (when last-event-id
            (replay-events ss os last-event-id))
          ;; Only drain the pending queue if the replay write didn't
          ;; detach the stream.
          (when (identical? os @(:os ss))
            (write-responses ss os q nil)))
        (when sync?
          (close-os ss os))))))
