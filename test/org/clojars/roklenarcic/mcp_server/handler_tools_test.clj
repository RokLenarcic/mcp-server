(ns org.clojars.roklenarcic.mcp-server.handler-tools-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.tools :as tools]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
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
          slim (fn [r] (update r :tools #(mapv (fn [t] (select-keys t [:name :description])) %)))]
      (server/add-tool session (server/tool "charlie" "C" (server/obj-schema nil {} []) (fn [_ _] nil)))
      (server/add-tool session (server/tool "alpha" "A" (server/obj-schema nil {} []) (fn [_ _] nil)))
      (server/add-tool session (server/tool "bravo" "B" (server/obj-schema nil {} []) (fn [_ _] nil)))
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

(defn- mock-validator
  "Returns a SchemaValidator that returns the given errors seq on every call,
  or nil (valid) when errors is nil/empty.  Also records the last json-impl,
  schema and data it received in the supplied atoms."
  [errors & {:keys [json-impl-atom schema-atom data-atom]}]
  (reify p/SchemaValidator
    (-validate [_ json-impl schema data]
      (when json-impl-atom (reset! json-impl-atom json-impl))
      (when schema-atom (reset! schema-atom schema))
      (when data-atom (reset! data-atom data))
      (seq errors))))

(deftest tools-call-schema-validation-test
  (testing "No validator set → tool is called without validation"
    (let [session (atom {})
          called? (atom false)]
      (server/add-tool session (server/tool "t" "desc"
                                            (server/obj-schema "s" {:x (server/num-schema "x")} ["x"])
                                            (fn [_ _] (reset! called? true) "ok")))
      (tools/tools-call session {} {:name "t" :arguments {:x 1}})
      (is (true? @called?))))

  (testing "Validator returns nil (valid) → tool handler is invoked"
    (let [session (atom {::mcp/serde :test-serde})
          called? (atom false)
          json-impl-got (atom nil)
          schema-got (atom nil)
          data-got (atom nil)
          validator (mock-validator nil
                                    :json-impl-atom json-impl-got
                                    :schema-atom schema-got
                                    :data-atom data-got)]
      (server/add-tool session (server/tool "t" "desc"
                                            (server/obj-schema "s" {:x (server/num-schema "x")} ["x"])
                                            (fn [_ _] (reset! called? true) "ok")))
      (server/set-params-validator session validator)
      (tools/tools-call session {} {:name "t" :arguments {:x 42}})
      (is (true? @called?))
      (is (= :test-serde @json-impl-got) "validator received the session's json-impl")
      (is (contains? (:properties @schema-got) :x) "validator received the tool's inputSchema")
      (is (= {:x 42} @data-got) "validator received arguments")))

  (testing "Validator returns errors → invalid-params returned, handler not called"
    (let [session (atom {})
          called? (atom false)
          validator (mock-validator ["x must be a number" "x is required"])]
      (server/add-tool session (server/tool "t" "desc"
                                            (server/obj-schema "s" {:x (server/num-schema "x")} ["x"])
                                            (fn [_ _] (reset! called? true) "ok")))
      (server/set-params-validator session validator)
      (is (match? {:code -32602
                   :data "Schema validation failed: x must be a number; x is required"}
                  (tools/tools-call session {} {:name "t" :arguments {}})))
      (is (false? @called?))))

  (testing "Tool with no :inputSchema is not validated even when validator is set"
    (let [session (atom {})
          called? (atom false)
          validator (mock-validator ["should not see this"])]
      ;; Manually register a tool without an :inputSchema key
      (swap! session assoc-in [::mcp/handlers :tools "bare"]
             {:name "bare" :handler (fn [_ _] (reset! called? true) "ok")})
      (server/set-params-validator session validator)
      (tools/tools-call session {} {:name "bare" :arguments {}})
      (is (true? @called?))))

  (testing "set-params-validator with nil removes the validator"
    (let [session (atom {})
          validator (mock-validator ["oops"])]
      (server/add-tool session (server/tool "t" "desc"
                                            (server/obj-schema "s" {:x (server/num-schema "x")} ["x"])
                                            (fn [_ _] "ok")))
      (server/set-params-validator session validator)
      ;; First call with validator in place should fail validation
      (is (match? {:code -32602} (tools/tools-call session {} {:name "t" :arguments {}})))
      ;; Remove the validator
      (server/set-params-validator session nil)
      ;; Now the call should succeed (handler returns "ok" → text content)
      (is (match? {:isError false} (tools/tools-call session {} {:name "t" :arguments {:x 1}}))))))

(deftest tools-call-schema-validation-exception-test
  (testing "Validator that throws returns invalid-params, handler not called"
    (let [session (atom {})
          called? (atom false)
          exploding (reify p/SchemaValidator
                      (-validate [_ _ _ _]
                        (throw (ex-info "boom" {:reason :test}))))]
      (server/add-tool session (server/tool "t" "desc"
                                            (server/obj-schema "s" {:x (server/num-schema "x")} ["x"])
                                            (fn [_ _] (reset! called? true) "ok")))
      (server/set-params-validator session exploding)
      (is (match? {:code -32602
                   :data "Schema validation failed: Schema validation error: boom"}
                  (tools/tools-call session {} {:name "t" :arguments {:x 1}})))
      (is (false? @called?)))))
