(ns org.clojars.roklenarcic.mcp-server.server.sse-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.server.sse :as sse])
  (:import (java.io ByteArrayOutputStream IOException OutputStream)
           (java.util.concurrent LinkedBlockingDeque)
           (java.util.concurrent.atomic AtomicLong)))

;; --- helpers ---------------------------------------------------------------

(defn- failing-os
  "An OutputStream whose every write and flush throw IOException."
  []
  (proxy [OutputStream] []
    (write
      ([_]      (throw (IOException. "broken pipe")))
      ([_ _ _]  (throw (IOException. "broken pipe"))))
    (flush [] (throw (IOException. "broken pipe")))))

(defn- flush-failing-os
  "An OutputStream whose writes succeed but whose flush throws."
  []
  (proxy [OutputStream] []
    (write
      ([_]     nil)
      ([_ _ _] nil))
    (flush [] (throw (IOException. "flush failed")))))

(defn- streamable-session
  "Builds a fresh StreamableSession for testing.
  The session atom contains only ::mcp/q and ::mcp/timeout-ms; all SSE
  transport state lives on the record fields."
  ([] (streamable-session 16 16))
  ([q-capacity replay-capacity]
   (let [session (atom {::mcp/q       (LinkedBlockingDeque. (int q-capacity))
                        ::mcp/timeout-ms 60000})]
     (sse/make-streamable-session session replay-capacity))))

(defn- record [event-id msg] [event-id 0 msg])

(defn- queue-vec [^LinkedBlockingDeque q]
  (vec (.toArray q)))

(defn- third [coll] (nth coll 2))

;; --- write-responses -------------------------------------------------------

(deftest write-responses-happy-path-test
  (testing "Drains the queue then writes the new record to the stream with id: + data:"
    (let [ss (streamable-session)
          q  (::mcp/q @(.session ss))
          os (ByteArrayOutputStream.)]
      (.offer q (record 1 "first"))
      (.offer q (record 2 "second"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q (record 3 "third"))
      (is (zero? (.size q)))
      (is (= "id: 1\ndata: first\n\nid: 2\ndata: second\n\nid: 3\ndata: third\n\n"
             (.toString os "UTF-8"))))))

(deftest write-responses-drain-only-test
  (testing "With nil record, drains the queue and writes nothing else"
    (let [ss (streamable-session)
          q  (::mcp/q @(.session ss))
          os (ByteArrayOutputStream.)]
      (.offer q (record 7 "only"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q nil)
      (is (zero? (.size q)))
      (is (= "id: 7\ndata: only\n\n" (.toString os "UTF-8"))))))

(deftest write-responses-appends-to-replay-on-success-test
  (testing "On successful write+flush the batch is appended to the replay buffer in order"
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q (record 3 "c"))
      (is (zero? (.size q)))
      (is (= [1 2 3]       (mapv first (queue-vec replay))))
      (is (= ["a" "b" "c"] (mapv third (queue-vec replay)))))))

(deftest write-responses-replay-buffer-respects-capacity-test
  (testing "Replay buffer at capacity drops oldest to make room for newest written events"
    (let [ss     (streamable-session 16 2)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)]
      (.offer replay (record 1 "old"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q (record 2 "x"))
      (sse/write-responses ss os q (record 3 "y"))
      (is (= [2 3] (mapv first (queue-vec replay)))
          "Oldest replay entry (id=1) dropped when capacity reached"))))

(deftest write-responses-detaches-and-partitions-on-write-failure-test
  (testing "When the OutputStream throws on the first write, the failing
event is treated as attempted (goes to the replay buffer) and the rest of
the batch is re-queued at the head."
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (failing-os)]
      (.offer q (record 1 "queued-msg"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q (record 2 "new-msg"))
      (is (nil? @(.os ss)) "Stream is detached after IOException")
      (is (= [[1 "queued-msg"]] (mapv (juxt first third) (queue-vec replay)))
          "The drained event whose write was attempted (and failed) is in the replay buffer")
      (is (= [[2 "new-msg"]] (mapv (juxt first third) (queue-vec q)))
          "The not-yet-attempted record is re-queued at the head"))))

(deftest write-responses-detaches-and-puts-all-in-replay-on-flush-failure-test
  (testing "When all writes succeed but flush throws, every event is
attempted-but-unconfirmed and goes to the replay buffer; nothing is
re-queued."
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (flush-failing-os)]
      (.offer q (record 1 "queued-msg"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q (record 2 "new-msg"))
      (is (nil? @(.os ss)) "Stream is detached on flush failure")
      (is (zero? (.size q)) "Nothing is re-queued; all events were attempted")
      (is (= [[1 "queued-msg"] [2 "new-msg"]]
             (mapv (juxt first third) (queue-vec replay)))
          "All events are appended to the replay buffer in order"))))

(deftest write-responses-no-msg-failure-test
  (testing "Drain-only failure (no new record): the failing event lands in
the replay buffer and the remainder is re-queued at the head."
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (failing-os)]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q nil)
      (is (nil? @(.os ss)))
      (is (= [[1 "a"]] (mapv (juxt first third) (queue-vec replay))))
      (is (= [[2 "b"]] (mapv (juxt first third) (queue-vec q)))
          "Only the not-yet-attempted entry is re-queued"))))

(defn- write-injecting-failing-os
  "An OutputStream whose write injects `tail-record` into `q` and then throws.
  Used to simulate a concurrent enqueue arriving between the drain and the
  failure path inside write-responses."
  [q tail-record]
  (proxy [OutputStream] []
    (write
      ([_]     (.offer ^LinkedBlockingDeque q tail-record) (throw (IOException. "broken pipe")))
      ([_ _ _] (.offer ^LinkedBlockingDeque q tail-record) (throw (IOException. "broken pipe"))))
    (flush [] (throw (IOException. "broken pipe")))))

(deftest write-responses-requeue-preserves-order-with-tail-items-test
  (testing "The not-yet-attempted re-queued items land at the head, ahead
of any item enqueued concurrently after the drain. The attempted-and-failed
event ends up in the replay buffer."
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (write-injecting-failing-os q (record 99 "tail"))]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q nil)
      (is (nil? @(.os ss)))
      (is (= [[1 "a"]] (mapv (juxt first third) (queue-vec replay)))
          "The first event was attempted and failed; it goes to the replay buffer")
      (is (= [[2 "b"] [99 "tail"]] (mapv (juxt first third) (queue-vec q)))
          "Not-yet-attempted 'b' restored at head; concurrently-enqueued 'tail' stays at the tail"))))

(defn- write-after-n-failing-os
  "An OutputStream whose first `n` writes succeed and whose (n+1)-th write
  throws. Flush always succeeds (but won't be reached after a failed write).
  Lets a test exercise a mid-batch failure where `attempted` lands strictly
  between 0 and batch size."
  [n]
  (let [remaining (atom n)]
    (proxy [OutputStream] []
      (write
        ([_]     (if (pos? @remaining)
                   (do (swap! remaining dec) nil)
                   (throw (IOException. "broken pipe"))))
        ([_ _ _] (if (pos? @remaining)
                   (do (swap! remaining dec) nil)
                   (throw (IOException. "broken pipe")))))
      (flush [] nil))))

(deftest write-responses-partitions-mid-batch-failure-test
  (testing "When the OutputStream accepts the first k writes and then throws,
events 0..k go to the replay buffer (attempted-and-ambiguous, including the
one that threw) and events k+1..end are re-queued at the head."
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (write-after-n-failing-os 2)] ;; events 1 and 2 succeed, event 3 throws
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (.offer q (record 3 "c"))
      (.offer q (record 4 "d"))
      (sse/set-os! ss os)
      (sse/write-responses ss os q nil)
      (is (nil? @(.os ss)))
      (is (= [[1 "a"] [2 "b"] [3 "c"]]
             (mapv (juxt first third) (queue-vec replay)))
          "Successful writes plus the throwing write all land in the replay buffer")
      (is (= [[4 "d"]] (mapv (juxt first third) (queue-vec q)))
          "Only the not-yet-attempted event 'd' is re-queued"))))

;; --- replay-events ---------------------------------------------------------

(deftest replay-events-writes-only-events-newer-than-last-event-id-test
  (testing "replay-events writes only events with event-id > last-event-id, in order"
    (let [ss     (streamable-session)
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (.offer replay (record 3 "c"))
      (sse/set-os! ss os)
      (sse/replay-events ss os 1)
      (is (= "id: 2\ndata: b\n\nid: 3\ndata: c\n\n" (.toString os "UTF-8"))
          "Only events with id > 1 are replayed"))))

(deftest replay-events-skips-when-nothing-newer-test
  (testing "replay-events writes nothing when last-event-id is at or above the highest buffered id"
    (let [ss     (streamable-session)
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (sse/set-os! ss os)
      (sse/replay-events ss os 5)
      (is (= "" (.toString os "UTF-8"))))))

(deftest replay-events-noop-on-nil-last-event-id-test
  (testing "replay-events does nothing when last-event-id is nil"
    (let [ss     (streamable-session)
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (sse/set-os! ss os)
      (sse/replay-events ss os nil)
      (is (= "" (.toString os "UTF-8"))))))

(deftest replay-events-detaches-on-write-failure-but-leaves-buffer-test
  (testing "On IOException replay-events detaches the stream but leaves the replay buffer intact"
    (let [ss     (streamable-session)
          replay (.replay-buffer ss)
          os     (failing-os)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (sse/set-os! ss os)
      (sse/replay-events ss os 0)
      (is (nil? @(.os ss)))
      (is (= 2 (.size replay))
          "Replay buffer is preserved so a future reconnect can re-attempt"))))

;; --- send-to-client-fn -----------------------------------------------------

(deftest send-to-client-fn-assigns-monotonic-event-ids-test
  (testing "Each call assigns a strictly increasing event id from the AtomicLong counter"
    (let [ss    (streamable-session)
          q     (::mcp/q @(.session ss))
          send! (sse/send-to-client-fn ss)]
      (send! "a")
      (send! "b")
      (send! "c")
      (is (= [1 2 3]       (mapv first (queue-vec q)))
          "Event ids are monotonic, starting from 1 (AtomicLong incrementAndGet)")
      (is (= ["a" "b" "c"] (mapv third (queue-vec q)))))))

(deftest send-to-client-fn-drops-oldest-when-queue-full-test
  (testing "send-to-client-fn enqueues at the tail and drops the oldest entry when the bounded queue is full"
    (let [ss    (streamable-session 2 16)
          q     (::mcp/q @(.session ss))
          send! (sse/send-to-client-fn ss)]
      (send! "a")
      (send! "b")
      (send! "c")
      (is (= 2 (.size q)) "Capacity respected")
      (is (= ["b" "c"] (mapv third (queue-vec q)))
          "Oldest ('a') dropped to make room for newest"))))

(deftest send-to-client-fn-writes-immediately-when-stream-attached-test
  (testing "When GET stream is set, send-to-client-fn writes directly to the stream and does not enqueue"
    (let [ss     (streamable-session)
          q      (::mcp/q @(.session ss))
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)
          _      (sse/set-os! ss os)
          send!  (sse/send-to-client-fn ss)]
      (send! "live")
      (is (zero? (.size q)))
      (is (= "id: 1\ndata: live\n\n" (.toString os "UTF-8")))
      (is (= [[1 "live"]] (mapv (juxt first third) (queue-vec replay)))
          "Successfully written event lands in the replay buffer"))))

(deftest send-to-client-fn-noop-on-nil-msg-test
  (testing "Passing nil does not allocate an event id or touch the queue"
    (let [ss      (streamable-session)
          q       (::mcp/q @(.session ss))
          counter ^AtomicLong (.sse-next-event-id ss)
          send!   (sse/send-to-client-fn ss)]
      (send! nil)
      (is (zero? (.size q)))
      (is (zero? (.get counter))
          "Counter is not incremented when there is no message to send"))))

;; --- post-stream registration ---------------------------------------------

(deftest register-deregister-post-stream-roundtrip-test
  (testing "register-post-stream adds os to post-streams; deregister removes it"
    (let [ss  (streamable-session)
          os1 (ByteArrayOutputStream.)
          os2 (ByteArrayOutputStream.)]
      (is (empty? @(.post-streams ss)))
      (sse/register-post-stream ss os1)
      (is (= #{os1} @(.post-streams ss)))
      (sse/register-post-stream ss os2)
      (is (= #{os1 os2} @(.post-streams ss)))
      (sse/deregister-post-stream ss os1)
      (is (= #{os2} @(.post-streams ss)))
      (sse/deregister-post-stream ss os2)
      (is (empty? @(.post-streams ss))))))

(deftest deregister-post-stream-is-idempotent-test
  (testing "Deregistering a stream that is not registered is a no-op"
    (let [ss (streamable-session)
          os (ByteArrayOutputStream.)]
      (sse/deregister-post-stream ss os)
      (is (empty? @(.post-streams ss))
          "Disj on a missing entry must not throw"))))

;; --- send-to-client-fn routing priority -----------------------------------

(deftest send-to-client-fn-prefers-get-stream-over-post-stream-test
  (testing "When both a GET-SSE stream and a POST-SSE stream are attached,
the GET stream wins and the POST stream receives nothing."
    (let [ss      (streamable-session)
          get-os  (ByteArrayOutputStream.)
          post-os (ByteArrayOutputStream.)
          _       (sse/set-os! ss get-os)
          _       (sse/register-post-stream ss post-os)
          send!   (sse/send-to-client-fn ss)]
      (send! "msg")
      (is (= "id: 1\ndata: msg\n\n" (.toString get-os "UTF-8"))
          "GET-SSE stream received the message")
      (is (= "" (.toString post-os "UTF-8"))
          "POST-SSE stream received nothing")
      (is (= #{post-os} @(.post-streams ss))
          "POST stream remains registered"))))

(deftest send-to-client-fn-falls-back-to-post-stream-when-no-get-test
  (testing "With no GET-SSE stream attached, a registered POST-SSE stream
receives the event ephemerally; the queue is not touched and the replay
buffer is not appended (POST streams are not eligible for Last-Event-ID
resumption)."
    (let [ss      (streamable-session)
          q       (::mcp/q @(.session ss))
          replay  (.replay-buffer ss)
          post-os (ByteArrayOutputStream.)
          _       (sse/register-post-stream ss post-os)
          send!   (sse/send-to-client-fn ss)]
      (send! "msg")
      (is (= "id: 1\ndata: msg\n\n" (.toString post-os "UTF-8")))
      (is (zero? (.size q)) "Queue is not drained nor enqueued to")
      (is (zero? (.size replay)) "Replay buffer is not appended for POST writes")
      (is (= #{post-os} @(.post-streams ss))
          "Successful POST write does not deregister the stream"))))

(deftest send-to-client-fn-post-stream-write-failure-queues-event-test
  (testing "When the POST fallback write throws, the stream is deregistered
and the event is queued for the next attached stream; the replay buffer is
not touched."
    (let [ss      (streamable-session)
          q       (::mcp/q @(.session ss))
          replay  (.replay-buffer ss)
          post-os (failing-os)
          _       (sse/register-post-stream ss post-os)
          send!   (sse/send-to-client-fn ss)]
      (send! "msg")
      (is (empty? @(.post-streams ss))
          "Failed POST stream is deregistered")
      (is (= [[1 "msg"]] (mapv (juxt first third) (queue-vec q)))
          "Event is queued as fallback")
      (is (zero? (.size replay)) "Replay buffer is not appended"))))

(deftest send-to-client-fn-falls-back-to-queue-when-no-streams-test
  (testing "With neither GET-SSE nor POST-SSE streams attached, the message is queued"
    (let [ss    (streamable-session)
          q     (::mcp/q @(.session ss))
          send! (sse/send-to-client-fn ss)]
      (send! "msg")
      (is (= [[1 "msg"]] (mapv (juxt first third) (queue-vec q)))))))

;; --- send-to-client-fn binding-based routing ------------------------------

(deftest send-to-client-fn-bound-post-stream-wins-over-get-test
  (testing "When `*post-stream*` is bound to a registered POST stream, it
wins over the active GET-SSE stream (so handler-produced notifications
correlate with the originating request's response)."
    (let [ss       (streamable-session)
          get-os   (ByteArrayOutputStream.)
          bound-os (ByteArrayOutputStream.)
          _        (sse/set-os! ss get-os)
          _        (sse/register-post-stream ss bound-os)
          send!    (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (= "id: 1\ndata: msg\n\n" (.toString bound-os "UTF-8"))
          "Bound POST stream received the message")
      (is (= "" (.toString get-os "UTF-8"))
          "GET-SSE stream received nothing")
      (is (= #{bound-os} @(.post-streams ss))
          "Successful bound write does not deregister the stream"))))

(deftest send-to-client-fn-bound-post-stream-routes-without-get-test
  (testing "With no GET stream attached, a bound POST stream still wins
over a sibling POST stream registered for a different in-flight request."
    (let [ss         (streamable-session)
          bound-os   (ByteArrayOutputStream.)
          sibling-os (ByteArrayOutputStream.)
          _          (sse/register-post-stream ss bound-os)
          _          (sse/register-post-stream ss sibling-os)
          send!      (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (= "id: 1\ndata: msg\n\n" (.toString bound-os "UTF-8"))
          "Bound POST stream received the message")
      (is (= "" (.toString sibling-os "UTF-8"))
          "Sibling POST stream received nothing")
      (is (= #{bound-os sibling-os} @(.post-streams ss))
          "Neither stream was deregistered"))))

(deftest send-to-client-fn-bound-write-failure-falls-through-to-get-test
  (testing "When the bound POST stream's write fails, it self-deregisters
and routing falls through to the active GET-SSE stream."
    (let [ss       (streamable-session)
          get-os   (ByteArrayOutputStream.)
          bound-os (failing-os)
          _        (sse/set-os! ss get-os)
          _        (sse/register-post-stream ss bound-os)
          send!    (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (empty? @(.post-streams ss))
          "Failed bound stream is deregistered")
      (is (= "id: 1\ndata: msg\n\n" (.toString get-os "UTF-8"))
          "GET stream took over delivery"))))

(deftest send-to-client-fn-bound-write-failure-falls-through-to-fallback-test
  (testing "When the bound POST stream fails and no GET is attached,
routing iterates the other registered POST streams (excluding the
already-attempted bound stream)."
    (let [ss         (streamable-session)
          bound-os   (failing-os)
          sibling-os (ByteArrayOutputStream.)
          _          (sse/register-post-stream ss bound-os)
          _          (sse/register-post-stream ss sibling-os)
          send!      (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (= #{sibling-os} @(.post-streams ss))
          "Failed bound stream is deregistered; sibling remains")
      (is (= "id: 1\ndata: msg\n\n" (.toString sibling-os "UTF-8"))
          "Sibling POST stream took over delivery"))))

(deftest send-to-client-fn-bound-and-all-fallbacks-fail-queues-test
  (testing "When the bound POST stream and every fallback POST stream
fail, the message is queued for the next attached stream."
    (let [ss         (streamable-session)
          q          (::mcp/q @(.session ss))
          bound-os   (failing-os)
          sibling-os (failing-os)
          _          (sse/register-post-stream ss bound-os)
          _          (sse/register-post-stream ss sibling-os)
          send!      (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (empty? @(.post-streams ss))
          "Both failed POST streams are deregistered")
      (is (= [[1 "msg"]] (mapv (juxt first third) (queue-vec q)))
          "Event queued as last-resort fallback"))))

(deftest send-to-client-fn-iterates-multiple-post-streams-test
  (testing "Without a binding and no GET stream, routing iterates the
registered POST streams via `some` and short-circuits on the first
healthy delivery: exactly one of the streams receives the event."
    (let [ss    (streamable-session)
          os1   (ByteArrayOutputStream.)
          os2   (ByteArrayOutputStream.)
          _     (sse/register-post-stream ss os1)
          _     (sse/register-post-stream ss os2)
          send! (sse/send-to-client-fn ss)]
      (send! "msg")
      (let [s1 (.toString os1 "UTF-8")
            s2 (.toString os2 "UTF-8")]
        (is (or (and (= "id: 1\ndata: msg\n\n" s1) (= "" s2))
                (and (= "id: 1\ndata: msg\n\n" s2) (= "" s1)))
            "Exactly one POST stream receives the message; `some` short-circuits"))
      (is (= #{os1 os2} @(.post-streams ss))
          "Neither stream is deregistered (both writes succeeded or were skipped)"))))

(deftest send-to-client-fn-iterates-all-failing-post-streams-and-queues-test
  (testing "Without a binding, when no GET is attached and every POST
stream's write fails, all of them self-deregister and the event is
queued."
    (let [ss    (streamable-session)
          q     (::mcp/q @(.session ss))
          os1   (failing-os)
          os2   (failing-os)
          _     (sse/register-post-stream ss os1)
          _     (sse/register-post-stream ss os2)
          send! (sse/send-to-client-fn ss)]
      (send! "msg")
      (is (empty? @(.post-streams ss))
          "Both POST streams are deregistered")
      (is (= [[1 "msg"]] (mapv (juxt first third) (queue-vec q)))
          "Event queued as last-resort fallback"))))

(deftest send-to-client-fn-bound-without-get-or-others-test
  (testing "When `*post-stream*` is bound and is the only registered
stream, routing delivers directly to it (no queueing, no replay)."
    (let [ss       (streamable-session)
          q        (::mcp/q @(.session ss))
          replay   (.replay-buffer ss)
          bound-os (ByteArrayOutputStream.)
          _        (sse/register-post-stream ss bound-os)
          send!    (sse/send-to-client-fn ss)]
      (binding [sse/*post-stream* bound-os]
        (send! "msg"))
      (is (= "id: 1\ndata: msg\n\n" (.toString bound-os "UTF-8")))
      (is (zero? (.size q)) "Queue is not drained nor enqueued to")
      (is (zero? (.size replay)) "Replay buffer is not appended for POST writes")
      (is (= #{bound-os} @(.post-streams ss))
          "Successful bound write does not deregister"))))

;; --- write-post-response-and-deregister -----------------------------------

(deftest write-post-response-and-deregister-writes-and-removes-test
  (testing "Writes the response as a single SSE event and removes os from post-streams"
    (let [ss (streamable-session)
          os (ByteArrayOutputStream.)
          _  (sse/register-post-stream ss os)]
      (sse/write-post-response-and-deregister ss os "{\"jsonrpc\":\"2.0\"}")
      (is (= "id: 1\ndata: {\"jsonrpc\":\"2.0\"}\n\n" (.toString os "UTF-8")))
      (is (empty? @(.post-streams ss))))))

(deftest write-post-response-and-deregister-skips-replay-buffer-test
  (testing "POST response event is NOT appended to the replay buffer"
    (let [ss     (streamable-session)
          replay (.replay-buffer ss)
          os     (ByteArrayOutputStream.)
          _      (sse/register-post-stream ss os)]
      (sse/write-post-response-and-deregister ss os "{\"r\":1}")
      (is (zero? (.size replay))
          "POST stream events are ephemeral; no replay capability"))))

(deftest write-post-response-and-deregister-nil-msg-only-deregisters-test
  (testing "When msg is nil, nothing is written and no event id is allocated;
the stream is still deregistered."
    (let [ss      (streamable-session)
          counter ^AtomicLong (.sse-next-event-id ss)
          os      (ByteArrayOutputStream.)
          _       (sse/register-post-stream ss os)]
      (sse/write-post-response-and-deregister ss os nil)
      (is (= "" (.toString os "UTF-8")) "No bytes written")
      (is (zero? (.get counter)) "Event id counter is not incremented")
      (is (empty? @(.post-streams ss))
          "Stream is still deregistered"))))

(deftest write-post-response-and-deregister-swallows-ioexception-test
  (testing "IOException on the write is logged and swallowed so the
streaming body returns normally; the stream is still deregistered."
    (let [ss (streamable-session)
          os (failing-os)
          _  (sse/register-post-stream ss os)]
      (sse/write-post-response-and-deregister ss os "{\"r\":1}")
      (is (empty? @(.post-streams ss))
          "Stream deregistered even when write fails"))))
