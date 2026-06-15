(ns org.clojars.roklenarcic.mcp-server.handler-logging-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.logging :as logging]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]))

;; ---------------------------------------------------------------------------
;; loggable? predicate

(deftest loggable-test
  (testing "message at exactly the threshold is forwarded"
    (are [threshold msg-level] (logging/loggable? threshold msg-level)
      :debug     :debug
      :warning   :warning
      :emergency :emergency))

  (testing "message above the threshold is forwarded"
    (are [threshold msg-level] (logging/loggable? threshold msg-level)
      :debug   :info
      :debug   :emergency
      :warning :error
      :error   :critical))

  (testing "message below the threshold is suppressed"
    (are [threshold msg-level] (not (logging/loggable? threshold msg-level))
      :error   :debug
      :error   :info
      :error   :warning
      :warning :debug
      :warning :info)))

;; ---------------------------------------------------------------------------
;; do-log — client notification behaviour

(defn- make-session
  "Minimal session atom for logging tests."
  [logging-level]
  (atom {::mcp/logging-level logging-level
         ::mcp/serde          nil
         ::mcp/send-to-client (fn [_])}))

(deftest do-log-no-threshold-test
  (testing "nothing sent to client when logging-level is not configured"
    (let [notifications (atom [])
          session       (atom {::mcp/send-to-client (fn [_])})]
      (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification
                    (fn [_ method params] (swap! notifications conj {:method method :params params}))]
        (logging/do-log session :error "my-logger" "boom" nil))
      (is (empty? @notifications)))))

(deftest do-log-filtered-test
  (testing "message below threshold is NOT forwarded"
    (let [notifications (atom [])
          session       (make-session :error)]
      (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification
                    (fn [_ method params] (swap! notifications conj {:method method :params params}))]
        (logging/do-log session :debug "my-logger" "verbose detail" {:k 1}))
      (is (empty? @notifications))))

  (testing "message at threshold IS forwarded"
    (let [notifications (atom [])
          session       (make-session :warning)]
      (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification
                    (fn [_ method params] (swap! notifications conj {:method method :params params}))]
        (logging/do-log session :warning "my-logger" "heads up" nil))
      (is (= 1 (count @notifications)))))

  (testing "message above threshold IS forwarded"
    (let [notifications (atom [])
          session       (make-session :warning)]
      (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification
                    (fn [_ method params] (swap! notifications conj {:method method :params params}))]
        (logging/do-log session :error "my-logger" "bad thing" {:detail "x"}))
      (is (= 1 (count @notifications))))))

(deftest do-log-notification-shape-test
  (testing "notification carries actual message level, not the threshold"
    (let [notifications (atom [])
          session       (make-session :warning)]   ; threshold = :warning
      (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification
                    (fn [_ method params] (swap! notifications conj {:method method :params params}))]
        ;; log at :error while threshold is :warning — level in notification must be "error"
        (logging/do-log session :error "svc" "something failed" nil))
      (is (match? [{:method "notifications/message"
                    :params {:level  "error"
                             :logger "svc"
                             :data   {:message "something failed"}}}]
                  @notifications)))))

;; ---------------------------------------------------------------------------
;; logging/setLevel handler

(deftest logging-set-level-test
  (testing "valid level is stored in session"
    (let [session (atom {})]
      (logging/logging-set-level session nil {:level "warning"})
      (is (= :warning (::mcp/logging-level @session)))))

  (testing "valid level returns empty map"
    (let [session (atom {})]
      (is (= {} (logging/logging-set-level session nil {:level "debug"})))))

  (testing "invalid level returns an error"
    (let [session (atom {})
          result  (logging/logging-set-level session nil {:level "LOUD"})]
      (is (match? {:code    -32602
                   :message "Invalid Params"
                   :data    string?}
                  result)))))
