(ns org.clojars.roklenarcic.mcp-server.handler-tools-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.tools :as tools]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]))

(def sample-tool
  (server/tool "calculator"
               "A simple calculator tool"
               (server/obj-schema "Calculator parameters"
                                  {:operation (server/str-schema "Math operation" nil)
                                   :a (server/num-schema "First number")
                                   :b (server/num-schema "Second number")}
                                  ["operation" "a" "b"])
               (fn [exchange params]
                 (case (:operation params)
                   "add" (+ (:a params) (:b params))
                   "multiply" (* (:a params) (:b params))
                   "divide" (if (zero? (:b params))
                              (c/tool-error "Division by zero")
                              (/ (:a params) (:b params)))))))

(deftest tools-list-test
  (testing "List tools when none exist"
    (let [session (atom {})]
      (is (= {:tools []} (tools/tools-list session {})))
      (server/add-tool session sample-tool)
      (is (match? {:tools [{:description "A simple calculator tool"
                            :handler some?
                            :inputSchema {:description "Calculator parameters"
                                          :properties {:a {:description "First number"
                                                           :type "number"}
                                                       :b {:description "Second number"
                                                           :type "number"}
                                                       :operation {:description "Math operation"
                                                                   :type "string"}}
                                          :required ["operation"
                                                     "a"
                                                     "b"]
                                          :type "object"}
                            :name "calculator"}]}
                  (tools/tools-list session {}))))))

(deftest tools-call-test
  (testing "Call existing tool successfully"
    (let [session (atom {})]
      (server/add-tool session sample-tool)
      (is (= {:content [{:text "8"
                         :type "text"}]
              :isError false})
          (tools/tools-call session {:name "calculator"
                                     :arguments {:operation "add" :a 5 :b 3}}))
      (is (= {:content [{:text "Division by zero"
                         :type "text"}]
              :isError true}
             (tools/tools-call session {:name "calculator"
                                        :arguments {:operation "divide"
                                                    :a 10
                                                    :b 0}})))
      (is (match? {:code -32602 :message "Invalid Params" :data "Tool non-existent not found"}
                  (tools/tools-call session {:name "non-existent" :arguments {}})))
      (server/add-tool session (server/tool "complex-tool"
                                            "Returns various content types"
                                            (server/obj-schema "No params" {} [])
                                            (fn [exchange params]
                                              [(c/text-content "Text result" 1.0 :user)
                                               (c/image-content (byte-array [1 2 3]) "image/png" 2.0 :assistant)
                                               "Simple string"])))
      (is (= {:content [{:annotations {:audience ["user"]
                                       :priority 1.0}
                         :text "Text result"
                         :type "text"}
                        {:annotations {:audience ["assistant"]
                                       :priority 2.0}
                         :data "AQID"
                         :mimeType "image/png"
                         :type "image"}
                        {:text "Simple string"
                         :type "text"}]
              :isError false}
             (tools/tools-call session {:name "complex-tool" :arguments {}}))))))
