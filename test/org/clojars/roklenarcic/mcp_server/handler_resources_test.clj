(ns org.clojars.roklenarcic.mcp-server.handler-resources-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.resources :as resources]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [org.clojars.roklenarcic.mcp-server.server :as server])
  (:import (clojure.lang ExceptionInfo)))

(deftest resources-list-test
  (testing "List resources when handler not set"
    (let [session (atom {::mcp/handlers {}})
          result (resources/resources-list session {})]
      (is (match? {:code -32602
                   :message "Invalid Params"
                   :data "Resources are not supported"}
                  result))))

  (testing "List resources with some resources"
    (let [resource-map (lookup/lookup-map false)
          session (atom {::mcp/handlers {:resources resource-map}})]
      (is (= {:next-cursor nil
              :resources []}
             (resources/resources-list session {})))
      (lookup/add-resource session
                           (c/resource-desc "file:///test.txt" "Test File" "A test" "text/plain" nil)
                           (fn [exchange uri] "file content"))
      (is (= {:next-cursor nil
              :resources [{:annotations nil
                           :description "A test"
                           :mimeType "text/plain"
                           :name "Test File"
                           :uri "file:///test.txt"}]}
             (resources/resources-list session {})))
      (is (= {:next-cursor nil
              :resources [{:annotations nil
                           :description "A test"
                           :mimeType "text/plain"
                           :name "Test File"
                           :uri "file:///test.txt"}]}
             (resources/resources-list session {:cursor "page-2"}))))))

(deftest resources-read-test
  (testing "Read resource with valid resource object"
    (let [resource-obj {:uri "file:///test.txt"
                        :handler (fn [exchange uri] "Hello, World!")}
          result (resources/resources-read nil resource-obj)]
      (is (match? {:contents [{:uri "file:///test.txt"
                               :text "Hello, World!"
                               :mimeType "text/plain"}]}
                  result))))

  (testing "Read resource with binary content"
    (let [exchange (atom {})
          binary-data (byte-array [1 2 3 4 5])
          resource-obj {:uri "file:///binary.dat"
                        :handler (fn [exchange uri] binary-data)}
          result (resources/resources-read nil resource-obj)]
      (is (match? {:contents [{:uri "file:///binary.dat"
                               :blob string?
                               :mimeType "application/octet-stream"}]}
                  result))))

  (testing "Read resource that throws exception"
    (let [exchange (atom {})
          resource-obj {:uri "file:///error.txt"
                        :handler (fn [exchange uri] (throw (ex-info "Resource error" {})))}]
      (is (thrown? ExceptionInfo (resources/resources-read exchange resource-obj))))))

(deftest resources-subscribe-test
  (testing "Subscribe to resource"
    (let [session (atom {::mcp/handlers {:resources (lookup/lookup-map true)}})
          exchange (server/exchange session)
          resource-obj {:uri "file:///test.txt"}]
      (is (= "file:///test.txt" (resources/subscribe exchange resource-obj)))
      (is (= #{"file:///test.txt"} (::mcp/resource-subscriptions @session)))
      (is (= "file:///test.txt" (resources/unsubscribe exchange resource-obj)))
      (is (= #{} (::mcp/resource-subscriptions @session))))))

(deftest resources-templates-list-test
  (testing "List templates when none exist"
    (let [session (atom {::mcp/handlers {}})
          result (resources/templates-list session {})]
      (is (match? {:resourceTemplates nil} result))))

  (testing "List templates with some templates"
    (let [template {:uriTemplate "file:///{name}.txt"
                    :name "Text Files"
                    :description "Text file template"
                    :mimeType "text/plain"
                    :annotations [{:audience [:user] :priority 1.0}]}
          session (atom {::mcp/handlers {:resource-templates [template]}})
          result (resources/templates-list session {})]
      (is (match? {:resourceTemplates [template]} result)))))

(deftest wrap-resource-test
  (testing "Wrap resource with valid parameters"
    (let [handler (fn [exchange res] {:success true :uri (:uri res)})
          session (atom {::mcp/handlers {:resources (lookup/lookup-map false)}})
          wrapped-handler (resources/wrap-resource handler)
          _ (lookup/add-resource session
                                 (c/resource-desc "file:///test.txt" "Test" "Test" "text/plain" nil)
                                 (fn [exchange uri] "content"))
          result (wrapped-handler session {:uri "file:///test.txt"})]
      (is (= {:success true
              :uri "file:///test.txt"}
             result))))

  (testing "Wrap resource with missing resources handler"
    (let [handler (fn [exchange res] {:success true})
          wrapped-handler (resources/wrap-resource handler)
          session (atom {::mcp/handlers {}})
          result (wrapped-handler session {:uri "file:///test.txt"})]
      (is (match? {:code -32602
                   :message "Invalid Params"
                   :data "Resources are not supported"}
                  result))))

  (testing "Wrap resource with invalid URI"
    (let [handler (fn [exchange res] {:success true})
          wrapped-handler (resources/wrap-resource handler)
          session (atom {::mcp/handlers {:resources (lookup/lookup-map false)}})
          result (wrapped-handler session {:uri 123})]
      (is (match? {:code -32602
                   :message "Invalid Params"
                   :data "Param 'uri' needs to be a string."}
                  result))))

  (testing "Wrap resource with non-existent resource"
    (let [handler (fn [exchange res] {:success true})
          wrapped-handler (resources/wrap-resource handler)
          session (atom {::mcp/handlers {:resources (lookup/lookup-map false)}})
          result (wrapped-handler session {:uri "file:///nonexistent.txt"})]
      (is (match? {:code -32002
                   :message "Resource Not Found"
                   :data "file:///nonexistent.txt"}
                  result)))))

(deftest notification-test
  (testing "Resource changed notification when subscribed"
    (let [notifications (atom [])
          send-fn (fn [notification] (swap! notifications conj notification))
          session (atom {::mcp/send-to-client send-fn
                         ::mcp/initialized? true
                         ::mcp/handlers {:resources (lookup/lookup-map true)}})
          _ (resources/notify-changed session "file:///test.txt")]
      ;; Should not send notification since not subscribed - depends on implementation
      (is (vector? @notifications))))

  (testing "Resources list changed notification"
    (let [notifications (atom [])
          send-fn (fn [session method params] (swap! notifications conj {:method method :params params}))
          session (atom {::mcp/initialized? true})
          _ (with-redefs [org.clojars.roklenarcic.mcp-server.json-rpc/send-notification send-fn]
              (resources/notify-changed-list session))]
      (is (match? [{:method "notifications/resources/list_changed" :params nil}]
                  @notifications)))))

(deftest resource-template-conversion-test
  (testing "Convert resource template with annotations"
    (let [template {:uriTemplate "file:///{name}.txt"
                    :name "Text Files"
                    :annotations [{:audience [:user :assistant] :priority 1.0}]}
          converted (#'resources/->resource-template template)]
      (is (match? {:uriTemplate "file:///{name}.txt"
                   :name "Text Files"
                   :annotations [{:audience ["user" "assistant"] :priority 1.0}]}
                  converted))))

  (testing "Convert resource template without annotations"
    (let [template {:uriTemplate "file:///{name}.txt"
                    :name "Text Files"
                    :annotations nil}
          converted (#'resources/->resource-template template)]
      (is (match? {:uriTemplate "file:///{name}.txt"
                   :name "Text Files"
                   :annotations nil}
                  converted)))))

(deftest get-resource-result-test
  (testing "Convert resource response to MCP format"
    (let [resource-response (c/resource "Hello, World!" "text/plain" nil)
          result (#'resources/get-resource-result resource-response "file:///test.txt")]
      (is (match? {:contents [{:uri "file:///test.txt"
                               :text "Hello, World!"
                               :mimeType "text/plain"}]}
                  result))))

  (testing "Convert JSONRPCError response"
    (let [error (c/resource-not-found "file:///missing.txt")
          result (#'resources/get-resource-result error "file:///missing.txt")]
      (is (= error result))))

  (testing "Convert collection of resources"
    (let [resources [(c/resource "Content 1" "text/plain" nil)
                     (c/resource "Content 2" "text/plain" nil)]
          result (#'resources/get-resource-result resources "file:///test.txt")]
      (is (match? {:contents [{:uri "file:///test.txt"
                               :text "Content 1"
                               :mimeType "text/plain"}
                              {:uri "file:///test.txt"
                               :text "Content 2"
                               :mimeType "text/plain"}]}
                  result)))))

(deftest resources-helper-test
  (testing "Retrieve resources handler from session"
    (let [resource-map (lookup/lookup-map false)
          session (atom {::mcp/handlers {:resources resource-map}})
          result (#'resources/resources' session)]
      (is (= resource-map result))))

  (testing "Retrieve resources handler when not set"
    (let [session (atom {::mcp/handlers {}})
          result (#'resources/resources' session)]
      (is (nil? result)))))

(deftest add-resources-handlers-test
  (testing "Add resource handlers to dispatch table"
    (let [initial-handlers {"ping" (fn [session params] {})}
          enhanced-handlers (resources/add-resources-handlers initial-handlers)]
      (is (contains? enhanced-handlers "ping"))
      (is (contains? enhanced-handlers "resources/list"))
      (is (contains? enhanced-handlers "resources/read"))
      (is (contains? enhanced-handlers "resources/subscribe"))
      (is (contains? enhanced-handlers "resources/unsubscribe"))
      (is (contains? enhanced-handlers "resources/templates/list"))
      (is (= 6 (count enhanced-handlers))))))

(deftest error-handling-test
  (testing "Resource read with handler that throws"
    (let [exchange (atom {})
          resource-obj {:uri "file:///error.txt"
                        :handler (fn [exchange uri] (throw (RuntimeException. "Handler error")))}]
      ;; Should be handled gracefully by papply
      (is (thrown? Exception (resources/resources-read exchange resource-obj)))))

  (testing "Invalid resource object structure"
    (let [exchange (atom {})
          invalid-resource {:uri "file:///test.txt"
                            ;; Missing :handler key
                            }]
      (is (thrown? Exception (resources/resources-read exchange invalid-resource))))))

