(ns org.clojars.roklenarcic.mcp-server.util-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.util :as util]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m])
  (:import (java.util.concurrent CompletableFuture ExecutionException)))

(deftest papply-test
  (testing "papply with immediate value"
    (let [result (util/papply "immediate value" identity)]
      (is (= "immediate value" result))))

  (testing "papply with CompletableFuture"
    (let [future (CompletableFuture/completedFuture "future value")
          result (util/papply future identity)]
      (is (instance? CompletableFuture result))
      (is (= "future value" @result))))

  (testing "papply with function transformation"
    (let [result (util/papply 5 (fn [x] (* x 2)))]
      (is (= 10 result))))

  (testing "papply with CompletableFuture and transformation"
    (let [future (CompletableFuture/completedFuture 10)
          result (util/papply future (fn [x] (+ x 5)))]
      (is (instance? CompletableFuture result))
      (is (= 15 @result))))

  (testing "papply with failed CompletableFuture"
    (let [failed-future (CompletableFuture.)
          _ (.completeExceptionally failed-future (RuntimeException. "Test error"))
          result (util/papply failed-future identity)]
      (is (instance? CompletableFuture result))
      (is (thrown? ExecutionException @result))))

  (testing "papply with function that throws"
    (is (thrown? RuntimeException (util/papply 5 (fn [x] (throw (RuntimeException. "Function error")))))))

  (testing "papply with CompletableFuture and function that throws"
    (let [future (CompletableFuture/completedFuture 10)
          result (util/papply future (fn [x] (throw (RuntimeException. "Transform error"))))]
      (is (instance? CompletableFuture result))
      (is (thrown? ExecutionException @result)))))

(deftest pcatch-test
  (testing "pcatch with immediate value (no exception)"
    (let [result (util/pcatch "no error" (fn [ex] "caught"))]
      (is (= "no error" result))))

  (testing "pcatch with CompletableFuture (no exception)"
    (let [future (CompletableFuture/completedFuture "no error")
          result (util/pcatch future (fn [ex] "caught"))]
      (is (instance? CompletableFuture result))
      (is (= "no error" @result))))

  (testing "pcatch with immediate exception"
    (let [ex (RuntimeException. "Test error")]
      (is (thrown? RuntimeException (util/pcatch (throw ex) (fn [caught-ex] (str "Caught: " (.getMessage caught-ex))))))))

  (testing "pcatch with failed CompletableFuture"
    (let [failed-future (CompletableFuture.)
          _ (.completeExceptionally failed-future (RuntimeException. "Future error"))
          result (util/pcatch failed-future (fn [ex] (str "Caught: " (.getMessage ex))))]
      (is (instance? CompletableFuture result))
      (is (= "Caught: Future error" @result))))

  (testing "pcatch with exception handler that throws"
    (let [failed-future (CompletableFuture.)
          _ (.completeExceptionally failed-future (RuntimeException. "Original error"))
          result (util/pcatch failed-future (fn [ex] (throw (RuntimeException. "Handler error"))))]
      (is (instance? CompletableFuture result))
      (is (thrown? ExecutionException @result)))))

(deftest future-composition-test
  (testing "papply and pcatch composition"
    (let [future (CompletableFuture/completedFuture 5)
          result (-> future
                     (util/papply (fn [x] (* x 2)))
                     (util/papply (fn [x] (+ x 3)))
                     (util/pcatch (fn [ex] "error")))]
      (is (instance? CompletableFuture result))
      (is (= 13 @result))))

  (testing "papply and pcatch with error in chain"
    (let [future (CompletableFuture/completedFuture 5)
          result (-> future
                     (util/papply (fn [x] (* x 2)))
                     (util/papply (fn [x] (throw (RuntimeException. "Chain error"))))
                     (util/papply (fn [x] (+ x 3)))
                     (util/pcatch (fn [ex] "recovered")))]
      (is (instance? CompletableFuture result))
      (is (= "recovered" @result))))

  (testing "Multiple error handling"
    (let [future (CompletableFuture/completedFuture 10)
          result (-> future
                     (util/pcatch (fn [ex] "first catch"))
                     (util/papply (fn [x] (if (string? x) (throw (RuntimeException. x)) (* x 2))))
                     (util/pcatch (fn [ex] "second catch")))]
      (is (instance? CompletableFuture result))
      (is (= 20 @result)))))

(deftest performance-test
  (testing "papply performance with many operations"
    (let [start-time (System/nanoTime)
          result (reduce (fn [acc _] (util/papply acc inc))
                         0
                         (range 1000))
          end-time (System/nanoTime)]
      (is (= 1000 result))
      ;; Should complete reasonably quickly
      (is (< (- end-time start-time) 10000000)))) ; 10ms

  (testing "CompletableFuture chain performance"
    (let [start-time (System/nanoTime)
          initial-future (CompletableFuture/completedFuture 0)
          result (reduce (fn [future-acc _] (util/papply future-acc inc))
                         initial-future
                         (range 100))
          end-time (System/nanoTime)]
      (is (instance? CompletableFuture result))
      (is (= 100 @result))
      ;; Should complete reasonably quickly
      (is (< (- end-time start-time) 50000000)))) ; 50ms

  (testing "Error handling performance"
    (let [start-time (System/nanoTime)
          result (reduce (fn [acc _] 
                           (util/pcatch acc (fn [ex] 0)))
                         42
                         (range 1000))
          end-time (System/nanoTime)]
      (is (= 42 result))
      ;; Should complete reasonably quickly
      (is (< (- end-time start-time) 10000000))))) ; 10ms

(deftest edge-cases-test
  (testing "papply with nil value"
    (let [result (util/papply nil identity)]
      (is (nil? result))))

  (testing "papply with nil function"
    (is (thrown? Exception (util/papply "value" nil))))

  (testing "pcatch with nil value"
    (let [result (util/pcatch nil (fn [ex] "caught"))]
      (is (nil? result))))

  (testing "pcatch with nil handler"
    (is (= "value") (util/pcatch "value" nil)))

  (testing "papply with complex data structures"
    (let [data {:numbers [1 2 3] :text "hello"}
          result (util/papply data (fn [d] (update d :numbers #(map inc %))))]
      (is (= {:numbers [2 3 4] :text "hello"} result))))

  (testing "CompletableFuture with complex transformations"
    (let [future (CompletableFuture/completedFuture {:items []})
          result (-> future
                     (util/papply (fn [data] (assoc data :items [1 2 3])))
                     (util/papply (fn [data] (update data :items #(map (partial * 2) %))))
                     (util/papply (fn [data] (assoc data :sum (reduce + (:items data))))))]
      (is (instance? CompletableFuture result))
      (is (= {:items [2 4 6] :sum 12} @result)))))

(deftest concurrent-operations-test
  (testing "Concurrent papply operations"
    (let [futures (for [i (range 10)]
                    (future (util/papply i (fn [x] (* x x)))))
          results (map deref futures)]
      (is (= [0 1 4 9 16 25 36 49 64 81] results))))

  (testing "Mixed concurrent operations"
    (let [futures (for [i (range 5)]
                    (future
                      (-> (CompletableFuture/completedFuture i)
                          (util/papply (fn [x] (* x 2)))
                          (util/pcatch (fn [ex] -1))
                          (util/papply (fn [x] (+ x 10))))))
          results (map #(deref (deref %)) futures)]
      (is (= [10 12 14 16 18] results)))))

(deftest type-preservation-test
  (testing "papply preserves CompletableFuture type"
    (let [future (CompletableFuture/completedFuture "test")
          result (util/papply future identity)]
      (is (instance? CompletableFuture result))
      (is (= CompletableFuture (class result)))))

  (testing "pcatch preserves CompletableFuture type"
    (let [future (CompletableFuture/completedFuture "test")
          result (util/pcatch future (fn [ex] "error"))]
      (is (instance? CompletableFuture result))
      (is (= CompletableFuture (class result)))))

  (testing "papply with immediate value returns immediate result"
    (let [result (util/papply "immediate" identity)]
      (is (= "immediate" result))
      (is (not (instance? CompletableFuture result)))))

  (testing "pcatch with immediate value returns immediate result"
    (let [result (util/pcatch "immediate" (fn [ex] "error"))]
      (is (= "immediate" result))
      (is (not (instance? CompletableFuture result))))))
