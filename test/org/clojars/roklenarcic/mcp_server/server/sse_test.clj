(ns org.clojars.roklenarcic.mcp-server.server.sse-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.server.sse :as sse])
  (:import (java.io ByteArrayOutputStream IOException OutputStream)
           (java.util.concurrent LinkedBlockingDeque)))

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

(deftest write-responses-happy-path-test
  (testing "Drains the queue then writes the new message to the stream"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (ByteArrayOutputStream.)]
      (.offer q [0 "first"])
      (.offer q [0 "second"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q "third")
      (is (zero? (.size q)))
      (is (= "data: first\n\ndata: second\n\ndata: third\n\n"
             (.toString os "UTF-8"))))))

(deftest write-responses-drain-only-test
  (testing "With nil msg, drains the queue and writes nothing else"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (ByteArrayOutputStream.)]
      (.offer q [0 "only"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (zero? (.size q)))
      (is (= "data: only\n\n" (.toString os "UTF-8"))))))

(deftest write-responses-detaches-and-requeues-on-write-failure-test
  (testing "When the underlying stream's write fails, the drained queued and the new message are re-queued at the head in original order and the stream is detached"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (failing-os)]
      (.offer q [0 "queued-msg"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q "new-msg")
      (is (nil? (::mcp/os @session))
          "Stream is detached after IOException")
      (is (= 2 (.size q))
          "Both the drained queued entry and the new message are re-queued")
      (is (= "queued-msg" (second (.pollFirst q))))
      (is (= "new-msg" (second (.pollFirst q)))))))

(deftest write-responses-detaches-and-requeues-on-flush-failure-test
  (testing "When only flush fails, drained queued and new messages are re-queued at the head and the stream is detached"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (flush-failing-os)]
      (.offer q [0 "queued-msg"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q "new-msg")
      (is (nil? (::mcp/os @session))
          "Stream is detached on flush failure")
      (is (= 2 (.size q))
          "Both the drained queued entry and the new message are re-queued")
      (is (= "queued-msg" (second (.pollFirst q))))
      (is (= "new-msg" (second (.pollFirst q)))))))

(deftest write-responses-no-msg-failure-test
  (testing "Drain-only failure (no new msg) re-queues the drained entries in order and detaches"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (failing-os)]
      (.offer q [0 "a"])
      (.offer q [0 "b"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (nil? (::mcp/os @session)))
      (is (= 2 (.size q)))
      (is (= "a" (second (.pollFirst q))))
      (is (= "b" (second (.pollFirst q)))))))

(defn- write-injecting-failing-os
  "An OutputStream whose write injects `tail-msg` into `q` and then throws.
  Used to simulate a concurrent enqueue arriving between the drain and the
  failure path inside write-responses."
  [q tail-msg]
  (proxy [OutputStream] []
    (write
      ([_]     (.offer q [0 tail-msg]) (throw (IOException. "broken pipe")))
      ([_ _ _] (.offer q [0 tail-msg]) (throw (IOException. "broken pipe"))))
    (flush [] (throw (IOException. "broken pipe")))))

(deftest write-responses-requeue-preserves-order-with-tail-items-test
  (testing "Re-queued (originally drained) items land at the head, ahead of any items enqueued concurrently after the drain"
    (let [session (atom {})
          q (LinkedBlockingDeque. 16)
          os (write-injecting-failing-os q "tail")]
      (.offer q [0 "a"])
      (.offer q [0 "b"])
      (swap! session assoc ::mcp/os os)
      (sse/write-responses session os q nil)
      (is (nil? (::mcp/os @session)))
      (is (= ["a" "b" "tail"] (mapv second (vec (.toArray q))))
          "Originals 'a' 'b' restored at head; concurrently-enqueued 'tail' stays at tail"))))

(deftest send-to-client-fn-drops-oldest-when-queue-full-test
  (testing "send-to-client-fn enqueues at the tail and drops the oldest entry when the bounded queue is full"
    (let [session (atom {})
          q (LinkedBlockingDeque. 2)
          _ (swap! session assoc ::mcp/q q ::mcp/timeout-ms 60000)
          send! (sse/send-to-client-fn session)]
      (send! "a")
      (send! "b")
      (send! "c")
      (is (= 2 (.size q)) "Capacity respected")
      (is (= ["b" "c"] (mapv second (vec (.toArray q))))
          "Oldest ('a') dropped to make room for newest"))))

(deftest send-to-client-fn-writes-immediately-when-stream-attached-test
  (testing "When ::mcp/os is set, send-to-client-fn writes directly to the stream and does not enqueue"
    (let [session (atom {})
          q (LinkedBlockingDeque. 4)
          os (ByteArrayOutputStream.)
          _ (swap! session assoc ::mcp/q q ::mcp/os os ::mcp/timeout-ms 60000)
          send! (sse/send-to-client-fn session)]
      (send! "live")
      (is (zero? (.size q)))
      (is (= "data: live\n\n" (.toString os "UTF-8"))))))
