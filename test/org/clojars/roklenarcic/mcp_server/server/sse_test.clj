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

(defn- session-atom
  "Builds a fresh test session pre-populated with the keys sse functions
  expect: queue, replay buffer, event-id counter, send-mutex, timeout-ms."
  ([] (session-atom 16 16))
  ([q-capacity replay-capacity]
   (atom {::mcp/q (LinkedBlockingDeque. (int q-capacity))
          ::mcp/replay-buffer (LinkedBlockingDeque. (int replay-capacity))
          ::mcp/sse-next-event-id (AtomicLong. 0)
          ::mcp/send-mutex (Object.)
          ::mcp/timeout-ms 60000})))

(defn- record [event-id msg] [event-id 0 msg])

(defn- queue-vec [^LinkedBlockingDeque q]
  (vec (.toArray q)))

(defn- third [coll] (nth coll 2))

;; --- write-responses -------------------------------------------------------

(deftest write-responses-happy-path-test
  (testing "Drains the queue then writes the new record to the stream with id: + data:"
    (let [session (session-atom)
          q (::mcp/q @session)
          os (ByteArrayOutputStream.)]
      (.offer q (record 1 "first"))
      (.offer q (record 2 "second"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q (record 3 "third"))
      (is (zero? (.size q)))
      (is (= "id: 1\ndata: first\n\nid: 2\ndata: second\n\nid: 3\ndata: third\n\n"
             (.toString os "UTF-8"))))))

(deftest write-responses-drain-only-test
  (testing "With nil record, drains the queue and writes nothing else"
    (let [session (session-atom)
          q (::mcp/q @session)
          os (ByteArrayOutputStream.)]
      (.offer q (record 7 "only"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (zero? (.size q)))
      (is (= "id: 7\ndata: only\n\n" (.toString os "UTF-8"))))))

(deftest write-responses-appends-to-replay-on-success-test
  (testing "On successful write+flush the batch is appended to the replay buffer in order"
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q (record 3 "c"))
      (is (zero? (.size q)))
      (is (= [1 2 3] (mapv first (queue-vec replay))))
      (is (= ["a" "b" "c"] (mapv third (queue-vec replay)))))))

(deftest write-responses-replay-buffer-respects-capacity-test
  (testing "Replay buffer at capacity drops oldest to make room for newest written events"
    (let [session (session-atom 16 2)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)]
      (.offer replay (record 1 "old"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q (record 2 "x"))
      (sse/write-responses session os q (record 3 "y"))
      (is (= [2 3] (mapv first (queue-vec replay)))
          "Oldest replay entry (id=1) dropped when capacity reached"))))

(deftest write-responses-detaches-and-partitions-on-write-failure-test
  (testing "When the OutputStream throws on the first write, the failing
event is treated as attempted (goes to the replay buffer) and the rest of
the batch is re-queued at the head."
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (failing-os)]
      (.offer q (record 1 "queued-msg"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q (record 2 "new-msg"))
      (is (nil? (::mcp/os @session)) "Stream is detached after IOException")
      (is (= [[1 "queued-msg"]] (mapv (juxt first third) (queue-vec replay)))
          "The drained event whose write was attempted (and failed) is in the replay buffer")
      (is (= [[2 "new-msg"]] (mapv (juxt first third) (queue-vec q)))
          "The not-yet-attempted record is re-queued at the head"))))

(deftest write-responses-detaches-and-puts-all-in-replay-on-flush-failure-test
  (testing "When all writes succeed but flush throws, every event is
attempted-but-unconfirmed and goes to the replay buffer; nothing is
re-queued."
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (flush-failing-os)]
      (.offer q (record 1 "queued-msg"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q (record 2 "new-msg"))
      (is (nil? (::mcp/os @session)) "Stream is detached on flush failure")
      (is (zero? (.size q)) "Nothing is re-queued; all events were attempted")
      (is (= [[1 "queued-msg"] [2 "new-msg"]]
             (mapv (juxt first third) (queue-vec replay)))
          "All events are appended to the replay buffer in order"))))

(deftest write-responses-no-msg-failure-test
  (testing "Drain-only failure (no new record): the failing event lands in
the replay buffer and the remainder is re-queued at the head."
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (failing-os)]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (nil? (::mcp/os @session)))
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
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (write-injecting-failing-os q (record 99 "tail"))]
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (nil? (::mcp/os @session)))
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
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (write-after-n-failing-os 2)] ;; events 1 and 2 succeed, event 3 throws
      (.offer q (record 1 "a"))
      (.offer q (record 2 "b"))
      (.offer q (record 3 "c"))
      (.offer q (record 4 "d"))
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (nil? (::mcp/os @session)))
      (is (= [[1 "a"] [2 "b"] [3 "c"]]
             (mapv (juxt first third) (queue-vec replay)))
          "Successful writes plus the throwing write all land in the replay buffer")
      (is (= [[4 "d"]] (mapv (juxt first third) (queue-vec q)))
          "Only the not-yet-attempted event 'd' is re-queued"))))

;; --- replay-events ---------------------------------------------------------

(deftest replay-events-writes-only-events-newer-than-last-event-id-test
  (testing "replay-events writes only events with event-id > last-event-id, in order"
    (let [session (session-atom)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (.offer replay (record 3 "c"))
      (swap! session assoc ::mcp/os os)
      (sse/replay-events session os 1)
      (is (= "id: 2\ndata: b\n\nid: 3\ndata: c\n\n" (.toString os "UTF-8"))
          "Only events with id > 1 are replayed"))))

(deftest replay-events-skips-when-nothing-newer-test
  (testing "replay-events writes nothing when last-event-id is at or above the highest buffered id"
    (let [session (session-atom)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (swap! session assoc ::mcp/os os)
      (sse/replay-events session os 5)
      (is (= "" (.toString os "UTF-8"))))))

(deftest replay-events-noop-on-nil-last-event-id-test
  (testing "replay-events does nothing when last-event-id is nil"
    (let [session (session-atom)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)]
      (.offer replay (record 1 "a"))
      (swap! session assoc ::mcp/os os)
      (sse/replay-events session os nil)
      (is (= "" (.toString os "UTF-8"))))))

(deftest replay-events-detaches-on-write-failure-but-leaves-buffer-test
  (testing "On IOException replay-events detaches the stream but leaves the replay buffer intact"
    (let [session (session-atom)
          replay (::mcp/replay-buffer @session)
          os (failing-os)]
      (.offer replay (record 1 "a"))
      (.offer replay (record 2 "b"))
      (swap! session assoc ::mcp/os os)
      (sse/replay-events session os 0)
      (is (nil? (::mcp/os @session)))
      (is (= 2 (.size replay))
          "Replay buffer is preserved so a future reconnect can re-attempt"))))

;; --- send-to-client-fn -----------------------------------------------------

(deftest send-to-client-fn-assigns-monotonic-event-ids-test
  (testing "Each call assigns a strictly increasing event id from the AtomicLong counter"
    (let [session (session-atom)
          q (::mcp/q @session)
          send! (sse/send-to-client-fn session)]
      (send! "a")
      (send! "b")
      (send! "c")
      (is (= [1 2 3] (mapv first (queue-vec q)))
          "Event ids are monotonic, starting from 1 (AtomicLong incrementAndGet)")
      (is (= ["a" "b" "c"] (mapv third (queue-vec q)))))))

(deftest send-to-client-fn-drops-oldest-when-queue-full-test
  (testing "send-to-client-fn enqueues at the tail and drops the oldest entry when the bounded queue is full"
    (let [session (session-atom 2 16)
          q (::mcp/q @session)
          send! (sse/send-to-client-fn session)]
      (send! "a")
      (send! "b")
      (send! "c")
      (is (= 2 (.size q)) "Capacity respected")
      (is (= ["b" "c"] (mapv third (queue-vec q)))
          "Oldest ('a') dropped to make room for newest"))))

(deftest send-to-client-fn-writes-immediately-when-stream-attached-test
  (testing "When ::mcp/os is set, send-to-client-fn writes directly to the stream and does not enqueue"
    (let [session (session-atom)
          q (::mcp/q @session)
          replay (::mcp/replay-buffer @session)
          os (ByteArrayOutputStream.)
          _ (swap! session assoc ::mcp/os os)
          send! (sse/send-to-client-fn session)]
      (send! "live")
      (is (zero? (.size q)))
      (is (= "id: 1\ndata: live\n\n" (.toString os "UTF-8")))
      (is (= [[1 "live"]] (mapv (juxt first third) (queue-vec replay)))
          "Successfully written event lands in the replay buffer"))))

(deftest send-to-client-fn-noop-on-nil-msg-test
  (testing "Passing nil does not allocate an event id or touch the queue"
    (let [session (session-atom)
          q (::mcp/q @session)
          ^AtomicLong counter (::mcp/sse-next-event-id @session)
          send! (sse/send-to-client-fn session)]
      (send! nil)
      (is (zero? (.size q)))
      (is (zero? (.get counter))
          "Counter is not incremented when there is no message to send"))))
