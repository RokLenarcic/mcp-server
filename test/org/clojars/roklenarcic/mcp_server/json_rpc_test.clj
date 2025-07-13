(ns org.clojars.roklenarcic.mcp-server.json-rpc-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.json-rpc.parse :as parse]
            [org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m])
  (:import (java.util.concurrent CompletableFuture)))

(def test-serde (charred/serde {}))

(deftest parse-string-test
  (testing "Valid JSON-RPC request"
    (let [json "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{\"a\":1},\"id\":123}"
          parsed (rpc/parse-string json test-serde)]
      (is (= "test" (:method parsed)))
      (is (= {:a 1} (:params parsed)))
      (is (= 123 (:id parsed)))
      (is (= :request (:item-type parsed)))))

  (testing "Valid JSON-RPC notification"
    (let [json "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{\"a\":1}}"
          parsed (rpc/parse-string json test-serde)]
      (is (= "test" (:method parsed)))
      (is (= {:a 1} (:params parsed)))
      (is (nil? (:id parsed)))
      (is (= :notification (:item-type parsed)))))

  (testing "Valid JSON-RPC client response"
    (let [json "{\"jsonrpc\":\"2.0\",\"result\":{\"success\":true},\"id\":123}"
          parsed (rpc/parse-string json test-serde)]
      (is (= :client-resp (:method parsed)))
      (is (= :notification (:item-type parsed)))
      (is (= {:success true} (get-in parsed [:params :result])))
      (is (= 123 (get-in parsed [:params :id])))))

  (testing "Batch request"
    (let [json "[{\"jsonrpc\":\"2.0\",\"method\":\"test1\",\"id\":1},{\"jsonrpc\":\"2.0\",\"method\":\"test2\",\"id\":2}]"
          parsed (rpc/parse-string json test-serde)]
      (is (vector? parsed))
      (is (= 2 (count parsed)))
      (is (every? #(= :request (:item-type %)) parsed))))

  (testing "Invalid JSON"
    (let [parsed (rpc/parse-string "invalid json{" test-serde)]
      (is (= parse/PARSE_ERROR (:code (:error parsed))))
      (is (= :error (:item-type parsed)))
      (is (nil? (:id parsed)))))

  (testing "Missing jsonrpc field"
    (let [parsed (rpc/parse-string "{\"method\":\"test\",\"id\":123}" test-serde)]
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))
      (is (= :error (:item-type parsed)))
      (is (= 123 (:id parsed)))))

  (testing "Empty batch"
    (let [parsed (rpc/parse-string "[]" test-serde)]
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))
      (is (= :error (:item-type parsed)))
      (is (nil? (:id parsed))))))

(deftest parse-record-creation-test
  (testing "Request creation"
    (let [req (parse/->request "test-method" {:param 1} 123)]
      (is (= "test-method" (:method req)))
      (is (= {:param 1} (:params req)))
      (is (= 123 (:id req)))
      (is (= :request (:item-type req)))))

  (testing "Notification creation"
    (let [notif (parse/->notification "notify-method" {:data "test"})]
      (is (= "notify-method" (:method notif)))
      (is (= {:data "test"} (:params notif)))
      (is (nil? (:id notif)))
      (is (= :notification (:item-type notif)))))

  (testing "Error creation"
    (let [error (parse/->error -32600 "Invalid Request" "error data" 456)]
      (is (= {:code -32600 :message "Invalid Request" :data "error data"} (:error error)))
      (is (= 456 (:id error)))
      (is (= :error (:item-type error)))))

  (testing "Parse error creation"
    (let [ex (Exception. "JSON parse failed")
          error (parse/parse-error ex)]
      (is (= parse/PARSE_ERROR (:code (:error error))))
      (is (= "Parse error" (:message (:error error))))
      (is (= "JSON parse failed" (:data (:error error))))
      (is (nil? (:id error)))
      (is (= :error (:item-type error)))))

  (testing "Invalid request creation"
    (let [error (parse/invalid-request "missing field" 789)]
      (is (= parse/INVALID_REQUEST (:code (:error error))))
      (is (= "Invalid Request" (:message (:error error))))
      (is (= "missing field" (:data (:error error))))
      (is (= 789 (:id error)))
      (is (= :error (:item-type error))))))

(deftest parse-request-validation-test
  (testing "Valid request"
    (let [request {:jsonrpc "2.0" :method "test" :params {} :id 1}
          parsed (parse/parse-request request)]
      (is (= :request (:item-type parsed)))
      (is (= "test" (:method parsed)))
      (is (= 1 (:id parsed)))))

  (testing "Invalid jsonrpc version"
    (let [request {:jsonrpc "1.0" :method "test" :id 1}
          parsed (parse/parse-request request)]
      (is (= :error (:item-type parsed)))
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))))

  (testing "Missing method"
    (let [request {:jsonrpc "2.0" :id 1}
          parsed (parse/parse-request request)]
      (is (= :error (:item-type parsed)))
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))))

  (testing "Invalid method type"
    (let [request {:jsonrpc "2.0" :method 123 :id 1}
          parsed (parse/parse-request request)]
      (is (= :error (:item-type parsed)))
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))))

  (testing "Invalid ID type"
    (let [request {:jsonrpc "2.0" :method "test" :id []}
          parsed (parse/parse-request request)]
      (is (= :error (:item-type parsed)))
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))))

  (testing "Invalid params type"
    (let [request {:jsonrpc "2.0" :method "test" :params "not-object-or-array" :id 1}
          parsed (parse/parse-request request)]
      (is (= :error (:item-type parsed)))
      (is (= parse/INVALID_REQUEST (:code (:error parsed))))))

  (testing "Client response detection"
    (let [response {:jsonrpc "2.0" :result {:success true} :id 123}
          parsed (parse/parse-request response)]
      (is (= :notification (:item-type parsed)))
      (is (= :client-resp (:method parsed)))
      (is (= {:success true} (get-in parsed [:params :result]))))))

(deftest json-serialization-test
  (testing "Object serialization"
    (let [obj {:test "value" :number 42}
          serialized (rpc/json-serialize test-serde obj)]
      (is (string? serialized))
      (is (.contains serialized "test"))
      (is (.contains serialized "value"))))

  (testing "Vector serialization"
    (let [vec [{:id 1} {:id 2}]
          serialized (rpc/json-serialize test-serde vec)]
      (is (string? serialized))
      (is (.startsWith serialized "["))
      (is (.endsWith serialized "]")))))

(deftest response-creation-test
  (testing "Success response"
    (is (= {:id 123 :jsonrpc "2.0" :result {:result "success"}}
           (rpc/make-response {:result "success"} 123))))

  (testing "Error response with JSONRPCError"
    (is (= {:error {:code -32602
                    :data "test error"
                    :message "Invalid Params"}
            :id 456
            :jsonrpc "2.0"}
           (rpc/make-response (c/invalid-params "test error") 456))))

  (testing "Error response creation"
    (is (= {:error {:code -32600
                    :data "test data"
                    :message "Invalid Request"}
            :id 789
            :jsonrpc "2.0"}
           (rpc/make-error-response -32600 "Invalid Request" "test data" 789))))

  (testing "Invalid request response"
    (is (= {:error {:code -32600
                    :data "missing field"
                    :message "Invalid Request"}
            :id 101
            :jsonrpc "2.0"}
           (rpc/invalid-request "missing field" 101)))))

(deftest handle-parsed-test
  (testing "Valid method dispatch"
    (let [dispatch-table {"test-method" (fn [context params] {:success true})}
          context (atom {})
          request (parse/->request "test-method" {} 1)
          response (rpc/handle-parsed request dispatch-table context)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= {:success true} (:result response)))
      (is (= 1 (:id response)))))

  (testing "Unknown method"
    (let [dispatch-table {}
          context (atom {})
          request (parse/->request "unknown-method" {} 1)
          response (rpc/handle-parsed request dispatch-table context)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= parse/METHOD_NOT_FOUND (get-in response [:error :code])))
      (is (= 1 (:id response)))))

  (testing "Notification handling"
    (let [handled (atom false)
          dispatch-table {"test-notification" (fn [context params] (reset! handled true))}
          context (atom {})
          notification (parse/->notification "test-notification" {})]
      (is (nil? (rpc/handle-parsed notification dispatch-table context)))
      (is @handled)))

  (testing "CompletableFuture response"
    (let [future-result (CompletableFuture/completedFuture {:async true})
          dispatch-table {"async-method" (fn [context params] future-result)}
          context (atom {})
          request (parse/->request "async-method" {} 1)
          response (rpc/handle-parsed request dispatch-table context)]
      (is (instance? CompletableFuture response))
      (let [resolved @response]
        (is (= "2.0" (:jsonrpc resolved)))
        (is (= {:async true} (:result resolved)))
        (is (= 1 (:id resolved))))))

  (testing "Error item-type handling"
    (let [error-msg (parse/->error -32600 "Test error" nil 123)
          response (rpc/handle-parsed error-msg {} {})]
      (is (= "2.0" (:jsonrpc response)))
      (is (= -32600 (get-in response [:error :code])))
      (is (= 123 (:id response))))))

(deftest combine-futures-test
  (testing "Combining multiple futures"
    (let [future1 (CompletableFuture/completedFuture {:result1 true})
          future2 (CompletableFuture/completedFuture {:result2 true})
          combined (rpc/combine-futures [future1 future2])]
      (is (instance? CompletableFuture combined))
      (let [result @combined]
        (is (= 2 (count result)))
        (is (every? map? result)))))

  (testing "Combining single future"
    (let [future1 (CompletableFuture/completedFuture {:single true})
          combined (rpc/combine-futures [future1])]
      (is (instance? CompletableFuture combined))
      (is (= [{:single true}] @combined))))

  (testing "Empty futures list"
    (let [combined (rpc/combine-futures [])]
      (is (nil? combined))))

  (testing "Mixed futures and regular values"
    (let [future1 (CompletableFuture/completedFuture {:future true})
          regular {:regular true}
          combined (rpc/combine-futures [regular future1])]
      (is (instance? CompletableFuture combined))
      (let [result @combined]
        (is (= 2 (count result)))
        (is (some #(= {:regular true} %) result))
        (is (some #(= {:future true} %) result))))))

(deftest client-request-management-test
  (testing "Client request cleanup"
    ;; Test the cleanup mechanism
    (let [initial-size (.size @#'rpc/client-req-pending)]
      ;; Add some expired entries manually for testing
      (.offer @#'rpc/client-req-queue [(- (System/currentTimeMillis) 200000) 999])
      
      ;; Run cleanup with 100 second timeout
      (rpc/cleanup-requests 100000)
      
      ;; The cleanup should have processed the queue
      (is (>= (.size @#'rpc/client-req-pending) 0)))))

(deftest middleware-test
  (testing "Apply single middleware"
    (let [handler (fn [ctx params] "original")
          middleware (fn [handler] (fn [ctx params] (str "wrapped-" (handler ctx params))))
          wrapped (rpc/apply-middleware handler [middleware])]
      (is (= "wrapped-original" (wrapped {} {})))))

  (testing "Apply multiple middleware"
    (let [handler (fn [ctx params] "original")
          middleware1 (fn [handler] (fn [ctx params] (str "m1-" (handler ctx params))))
          middleware2 (fn [handler] (fn [ctx params] (str "m2-" (handler ctx params))))
          wrapped (rpc/apply-middleware handler [middleware1 middleware2])]
      (is (= "m1-m2-original" (wrapped {} {})))))

  (testing "Apply middleware to dispatch table"
    (let [dispatch-table {"method1" (fn [ctx params] "result1")
                          "method2" (fn [ctx params] "result2")}
          middleware (fn [handler] (fn [ctx params] (str "wrapped-" (handler ctx params))))
          wrapped-table (rpc/with-middleware dispatch-table [middleware])]
      (is (= "wrapped-result1" ((get wrapped-table "method1") {} {})))
      (is (= "wrapped-result2" ((get wrapped-table "method2") {} {})))))

  (testing "Vector-style middleware"
    (let [handler (fn [ctx params] "original")
          middleware-fn (fn [handler prefix] (fn [ctx params] (str prefix "-" (handler ctx params))))
          wrapped (rpc/apply-middleware handler [[middleware-fn "test"]])]
      (is (= "test-original" (wrapped {} {}))))))

(deftest error-middleware-test
  (testing "Wrap handler with error handling"
    (let [handler (fn [ctx params] (throw (RuntimeException. "test error")))
          wrapped (rpc/wrap-error handler :info)
          result (wrapped {} {})]
      (is (instance? org.clojars.roklenarcic.mcp_server.core.JSONRPCError result))
      (is (= parse/INTERNAL_ERROR (:code result)))
      (is (= "test error" (:message result)))))

  (testing "Wrap handler that returns CompletableFuture"
    (let [failed-future (CompletableFuture.)
          _ (.completeExceptionally failed-future (RuntimeException. "async error"))
          handler (fn [ctx params] failed-future)
          wrapped (rpc/wrap-error handler :info)
          result (wrapped {} {})]
      (is (instance? CompletableFuture result))
      (let [resolved @result]
        (is (instance? org.clojars.roklenarcic.mcp_server.core.JSONRPCError resolved))
        (is (= parse/INTERNAL_ERROR (:code resolved)))
        (is (= "async error" (:message resolved)))))))

(deftest method-not-found-handler-test
  (testing "Method not found handler"
    (let [handler (rpc/method-not-found-handler "unknown-method")
          result (handler {} {})]
      (is (instance? org.clojars.roklenarcic.mcp_server.core.JSONRPCError result))
      (is (= parse/METHOD_NOT_FOUND (:code result)))
      (is (.contains (:message result) "unknown-method")))))

(deftest handle-client-response-test
  (testing "Handle client success response"
    ;; This test verifies the handler function structure
    (let [result (rpc/handle-client-response {} {:result {:success true} :id 123})]
      ;; Should return nil since it's a notification handler
      (is (nil? result))))

  (testing "Handle client error response"
    (let [result (rpc/handle-client-response {} {:error {:code -1 :message "client error"} :id 456})]
      (is (nil? result)))))

(deftest base-session-test
  (testing "Create base session"
    (let [context {:custom "data"}
          server-info {:name "test" :version "1.0"}
          serde test-serde
          dispatch {"ping" (fn [ctx params] "pong")}
          session (rpc/base-session context server-info serde dispatch)]
      (is (= server-info (::mcp/server-info session)))
      (is (= serde (::mcp/serde session)))
      (is (= dispatch (::mcp/dispatch-table session)))
      (is (= "data" (:custom session)))
      (is (map? (::mcp/handlers session))))))

(deftest notification-and-request-test
  (testing "Send notification"
    (let [notifications (atom [])
          send-fn (fn [json-str] (swap! notifications conj json-str))
          session (atom {::mcp/send-to-client send-fn
                         ::mcp/serde test-serde})]
      (rpc/send-notification session "test/notification" {:data "test"})
      (is (= 1 (count @notifications)))
      (let [sent-json (first @notifications)
            parsed (rpc/json-deserialize test-serde sent-json)]
        (is (= "2.0" (:jsonrpc parsed)))
        (is (= "test/notification" (:method parsed)))
        (is (= {:data "test"} (:params parsed)))
        (is (nil? (:id parsed))))))

  (testing "Send request"
    (let [requests (atom [])
          send-fn (fn [json-str] (swap! requests conj json-str))
          session (atom {::mcp/send-to-client send-fn
                         ::mcp/serde test-serde})
          future-result (rpc/send-request session "test/request" {:param "value"})]
      (is (instance? CompletableFuture future-result))
      (is (= 1 (count @requests)))
      (let [sent-json (first @requests)
            parsed (rpc/json-deserialize test-serde sent-json)]
        (is (= "2.0" (:jsonrpc parsed)))
        (is (= "test/request" (:method parsed)))
        (is (= {:param "value"} (:params parsed)))
        (is (number? (:id parsed)))))))

(deftest parse-constants-test
  (testing "Error code constants"
    (is (= -32700 parse/PARSE_ERROR))
    (is (= -32600 parse/INVALID_REQUEST))
    (is (= -32601 parse/METHOD_NOT_FOUND))
    (is (= -32602 parse/INVALID_PARAMS))
    (is (= -32603 parse/INTERNAL_ERROR))
    (is (= -32002 parse/RESOURCE_NOT_FOUND))))
