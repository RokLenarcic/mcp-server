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
      (is (= {:tools []} (tools/tools-list session {} {})))
      (server/add-tool session sample-tool)
      (is (match? {:tools [{:description "A simple calculator tool"
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
                  (tools/tools-list session {} {})))))
  (testing "Listed tools include :title and :outputSchema when provided"
    (let [session (atom {})
          out-schema {:type "object"
                      :properties {:result {:type "number"}}
                      :required ["result"]}]
      (server/add-tool session
                       (server/tool "structured-calc"
                                    "Calculator with structured output"
                                    (server/obj-schema "Args"
                                                       {:a (server/num-schema "A")
                                                        :b (server/num-schema "B")}
                                                       ["a" "b"])
                                    (fn [_ _] nil)
                                    :title "Calculator (structured)"
                                    :output-schema out-schema))
      (is (match? {:tools [{:name "structured-calc"
                            :title "Calculator (structured)"
                            :outputSchema out-schema}]}
                  (tools/tools-list session {} {}))))))

(deftest tools-list-pagination-test
  (testing "tools sorted and paginated when ::mcp/page-size is set"
    (let [session (atom {::mcp/page-size 1})
          slim  (fn [r] (update r :tools #(mapv (fn [t] (select-keys t [:name :description])) %)))]
      (server/add-tool session (server/tool "charlie" "C" (server/obj-schema nil {} []) (fn [_ _] nil)))
      (server/add-tool session (server/tool "alpha"   "A" (server/obj-schema nil {} []) (fn [_ _] nil)))
      (server/add-tool session (server/tool "bravo"   "B" (server/obj-schema nil {} []) (fn [_ _] nil)))
      (let [p1 (slim (tools/tools-list session {} {}))
            p2 (slim (tools/tools-list session {} {:cursor (:nextCursor p1)}))
            p3 (slim (tools/tools-list session {} {:cursor (:nextCursor p2)}))]
        (is (= {:tools [{:name "alpha" :description "A"}] :nextCursor "alpha"} p1))
        (is (= {:tools [{:name "bravo" :description "B"}] :nextCursor "bravo"} p2))
        (is (= {:tools [{:name "charlie" :description "C"}]} p3))))))

(deftest tools-call-test
  (testing "Call existing tool successfully"
    (let [session (atom {})]
      (server/add-tool session sample-tool)
      (is (= {:content [{:text "8"
                         :type "text"}]
              :isError false})
          (tools/tools-call session {} {:name "calculator"
                                        :arguments {:operation "add" :a 5 :b 3}}))
      (is (= {:content [{:text "Division by zero"
                         :type "text"}]
              :isError true}
             (tools/tools-call session {} {:name "calculator"
                                           :arguments {:operation "divide"
                                                       :a 10
                                                       :b 0}})))
      (is (match? {:code -32602 :message "Invalid Params" :data "Tool non-existent not found"}
                  (tools/tools-call session {} {:name "non-existent" :arguments {}})))
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
             (tools/tools-call session {} {:name "complex-tool" :arguments {}})))
      (server/add-tool session (server/tool "link-tool"
                                            "Returns a resource_link"
                                            (server/obj-schema "No params" {} [])
                                            (fn [exchange _]
                                              [(c/resource-link "file:///tmp/notes.md" "notes"
                                                                :title "Project Notes"
                                                                :description "Top-level notes"
                                                                :mime-type "text/markdown"
                                                                :priority 0.7
                                                                :audience :user)])))
      (is (= {:content [{:type "resource_link"
                         :uri "file:///tmp/notes.md"
                         :name "notes"
                         :annotations {:priority 0.7 :audience ["user"]}
                         :title "Project Notes"
                         :description "Top-level notes"
                         :mimeType "text/markdown"}]
              :isError false}
             (tools/tools-call session {} {:name "link-tool" :arguments {}})))
      (server/add-tool session (server/tool "structured-tool"
                                            "Returns structured + content"
                                            (server/obj-schema "No params" {} [])
                                            (fn [_ _]
                                              (c/tool-result
                                               [(c/text-content "Sum is 7")]
                                               {:result 7 :note "added"}))
                                            :output-schema {:type "object"
                                                            :properties {:result {:type "number"}
                                                                         :note {:type "string"}}}))
      (is (= {:content [{:type "text" :text "Sum is 7"}]
              :structuredContent {:result 7 :note "added"}
              :isError false}
             (tools/tools-call session {} {:name "structured-tool" :arguments {}})))
      (server/add-tool session (server/tool "meta-tool"
                                            "Returns structured + _meta"
                                            (server/obj-schema "No params" {} [])
                                            (fn [_ _]
                                              (c/tool-result
                                               [(c/text-content "ok")]
                                               {:result 42}
                                               :_meta {:com.example/trace-id "abc-123"
                                                       :other-key "v"}))
                                            :output-schema {:type "object"
                                                            :properties {:result {:type "number"}}}))
      (is (= {:content [{:type "text" :text "ok"}]
              :structuredContent {:result 42}
              :isError false
              :_meta {:com.example/trace-id "abc-123"
                      :other-key "v"}}
             (tools/tools-call session {} {:name "meta-tool" :arguments {}}))))))
