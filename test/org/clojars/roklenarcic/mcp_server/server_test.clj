(ns org.clojars.roklenarcic.mcp-server.server-test
  (:require [clojure.test :refer :all]
            [charred.api :as json]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.handler.init :as init]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
            [org.clojars.roklenarcic.mcp-server.test-inputs :as test-in]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m])
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent CompletableFuture)))

;; Helper function to read multiple responses
(defn read-responses
  ([stdout] (map json/read-json (line-seq stdout)))
  ([stdout n]
   (take n (read-responses stdout))))

;; Helper function to write a batch request
(defn write-batch [stdin requests]
  (doto stdin
    (.write (json/write-json-str requests))
    (.write "\n")
    (.flush)))

;; Helper function to write a raw JSON-RPC message (for notifications)
(defn write-raw-message [stdin message]
  (doto stdin
    (.write (json/write-json-str message))
    (.write "\n")
    (.flush)))

(defn add-some-resources [session]
  (server/set-resources-handler session (lookup/lookup-map true))
  (lookup/add-resource session
                       (c/resource-desc "http://resource.txt" "My Resource" "A big file of works" "text/plain" nil)
                       (fn [_ _] "Resource contents")))

(defn add-some-resource-templates [session]
  (server/add-resource-template session "file:///{temp}.txt" "General file" "General file template" "text/plain"
                                [{:audience [:user :assistant]
                                  :priority 3.4}]))

;; Helper function to perform a single request and read the response
(defn request-and-read
  ([method params] (request-and-read method params false))
  ([method params init?]
   (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
     (add-some-resources server)
     (add-some-resource-templates server)
     (when init? (test-in/initialize s))
     (test-in/print-req stdin method params)
     (.close stdin)
     (json/read-json (first (line-seq stdout))))))

;; Helper function to perform a request with custom message and read response
(defn raw-request-and-read [message]
  (let [{:keys [stdin stdout]} (test-in/create-server)]
    (write-raw-message stdin message)
    (.close stdin)
    (json/read-json (first (line-seq stdout)))))

;; Ping test
(deftest ping-test
  (is (match? {"jsonrpc" "2.0" "result" {} "id" int?}
              (request-and-read "ping" {}))))

;; Initialize test
(deftest initialize-test
  (testing "Initialize request"
    (is (match? {"jsonrpc" "2.0"
                 "result" {"protocolVersion" init/server-protocol-version
                           "capabilities" map?
                           "serverInfo" {"name" "Test MCP Server"
                                         "version" "1.0.0"}}
                 "id" int?}
                (request-and-read "initialize"
                                  {:protocolVersion init/server-protocol-version
                                   :capabilities {}
                                   :clientInfo {:name "Test Client" :version "1.0.0"}}))))

  (testing "Initialize with invalid protocol version"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "message" string?}
                 "id" int?}
                (request-and-read "initialize"
                                  {:protocolVersion "invalid-version"
                                   :capabilities {}
                                   :clientInfo {:name "Test Client" :version "1.0.0"}})))))
;; Initialized notification test
(deftest initialized-test
  (let [{:keys [stdin stdout server]} (test-in/create-server)]
    ;; First initialize
    (test-in/print-req stdin "initialize"
                       {:protocolVersion "2024-11-06"
                        :capabilities {}
                        :clientInfo {:name "Test Client" :version "1.0.0"}})
    ;; Read initialize response
    (is (match? {"error" {"code" -32600
                          "data" "Invalid protocol version 2024-11-06, supported version #{\"2025-03-26\" \"2025-06-18\" \"2024-11-05\"}"
                          "message" "Invalid Request"}
                 "id" int?
                 "jsonrpc" "2.0"}
                (json/read-json (first (line-seq stdout)))))

    ;; Send initialized notification (no id field for notifications)
    (write-raw-message stdin {:jsonrpc "2.0" :method "notifications/initialized" :params {}})

    (test-in/print-req stdin "initialize"
                       {:protocolVersion init/server-protocol-version
                        :capabilities {}
                        :clientInfo {:name "Test Client" :version "1.0.0"}})

    ;; Send initialized notification (no id field for notifications)
    (write-raw-message stdin {:jsonrpc "2.0" :method "notifications/initialized" :params {}})

    ;; Send a ping to verify server is still responsive
    (test-in/print-req stdin "ping" {})
    (.close stdin)

    ;; Should get ping response (no response for notification)
    (let [responses (read-responses stdout)]
      (is (match? {"jsonrpc" "2.0" "result" {} "id" int?}
                  (first responses))))))

;; List resources test
(deftest list-resources-test
  (testing "List resources when none configured"
    (let [{:keys [stdin stdout] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (test-in/print-req stdin "resources/list" {})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "error" {"data" "Resources are not supported"}
                   "id" int?}
                  (json/read-json (first (line-seq stdout)))))))
  (testing "List resources when some are available"
    (is (match? {"jsonrpc" "2.0"
                 "result" {"next-cursor" nil,
                           "resources"
                           [{"uri" "http://resource.txt",
                             "mimeType" "text/plain",
                             "name" "My Resource",
                             "annotations" nil,
                             "description" "A big file of works"}]}
                 "id" int?}
                (request-and-read "resources/list" {} true)))))

;; Read resource test
(deftest read-resource-test
  (testing "Read when resources unsupported"
    (let [{:keys [stdin stdout] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (test-in/print-req stdin "resources/read" {:uri "file:///nonexistent.txt"})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "error" {"code" int?
                            "data" "Resources are not supported"
                            "message" "Invalid Params"}
                   "id" int?}
                  (json/read-json (first (line-seq stdout)))))))
  (testing "Read non-existent resource"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "data" "file:///nonexistent.txt"
                          "message" "Resource Not Found"}
                 "id" int?}
                (request-and-read "resources/read" {:uri "file:///nonexistent.txt"} true))))
  (testing "Read text resource"
    (is (match? {"jsonrpc" "2.0"
                 "result" {"contents"
                           [{"uri" "http://resource.txt",
                             "text" "Resource contents",
                             "mimeType" "text/plain"}]}
                 "id" int?}
                (request-and-read "resources/read" {:uri "http://resource.txt"} true)))))

;; List resource templates test
(deftest list-resource-templates-test
  (is (match? {"id" int?
               "jsonrpc" "2.0"
               "result" {"resourceTemplates" [{"annotations" [{"audience" ["user"
                                                                           "assistant"]
                                                               "priority" 3.4}]
                                               "description" "General file template"
                                               "mimeType" "text/plain"
                                               "name" "General file"
                                               "uriTemplate" "file:///{temp}.txt"}]}}
              (request-and-read "resources/templates/list" {} true))))

;; List prompts test
(deftest list-prompts-test
  (testing "List prompts when none configured"
    (let [{:keys [stdin stdout] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (test-in/print-req stdin "prompts/list" {})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {"prompts" []}
                   "id" int?}
                  (json/read-json (first (line-seq stdout)))))))

  (testing "List prompts with cursor"
    (let [{:keys [stdin stdout] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (test-in/print-req stdin "prompts/list" {:cursor "next-page"})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {"prompts" vector?}
                   "id" int?}
                  (json/read-json (first (line-seq stdout))))))))

;; Get prompt test
(deftest get-prompt-test
  (testing "Get non-existent prompt"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "message" string?}
                 "id" int?}
                (request-and-read "prompts/get" {:name "non-existent-prompt"}))))
  (testing "Call prompt test"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (test-in/initialize s)

      (server/add-prompt server (server/prompt "My Prompt" "Bigliest prompt, trust me folks"
                                               {:nuts "Nut type"}
                                               {:bolts "Bolt type"}
                                               (fn [exchange arguments]
                                                 (c/prompt-resp "Great selection" [(str arguments) (c/image-content (byte-array [1 2 3])
                                                                                                                    "image/png" 4.5 :user)]))))
      (test-in/print-req stdin "prompts/list" {})
      (test-in/print-req stdin "prompts/get" {:name "My Prompt" :arguments {:nuts "Big nut" :bolts "Small bolt"}})
      (.close stdin)
      (is (match? [{"jsonrpc" "2.0" "method" "notifications/prompts/list_changed"}
                   {"id" int?,
                    "jsonrpc" "2.0",
                    "result"
                    {"prompts"
                     [{"name" "My Prompt",
                       "arguments"
                       [{"name" "nuts", "required" true, "description" "Nut type"}
                        {"name" "bolts", "required" false, "description" "Bolt type"}],
                       "description" "Bigliest prompt, trust me folks"}]}}
                   {"id" int?,
                    "jsonrpc" "2.0",
                    "result"
                    {"messages" [{"content" {"text" "{:nuts \"Big nut\", :bolts \"Small bolt\"}"}}
                                 {"content"
                                  {"annotations" {"priority" 4.5, "audience" ["user"]},
                                   "type" "image",
                                   "mimeType" "image/png",
                                   "data" "AQID"}}],
                     "description" "Great selection"}}]
                  (mapv json/read-json (line-seq stdout)))))))

(def tool1 (server/tool "Sum tool"
                        "Tool that sums two numbers"
                        (server/obj-schema "Params for tool" {:a (server/int-schema "First number")
                                                              :b (server/int-schema "Second number")}
                                           ["a" "b"])
                        (fn [exchange params]
                          (+ (:a params) (:b params)))))

(def tool2 (server/tool "Show image tool"
                        "Tool that shows an image"
                        (server/obj-schema "Params for tool"
                                           {:funny? (server/bool-schema "Should the image be funny?")}
                                           [])
                        (fn [exchange {:keys [funny?]}]
                          (c/image-content (byte-array (range 400)) "image/png" 0 :user))))

(def tool3 (server/tool "Error tool"
                        "Tool that has an error"
                        (server/obj-schema "Params for tool" {} [])
                        (fn [exchange params]
                          (throw (ex-info "A" {})))))

(def tool4 (server/tool "Coll return tool"
                        "Tool returns a lot of things"
                        (server/obj-schema "Params for tool" {} [])
                        (fn [exchange _]
                          ["A"
                           "C"
                           (c/text-content "HI" 1.5 :user)
                           (c/image-content (byte-array [1]) "image/jpeg" 1.5 [:user :assistant])
                           (c/audio-content (byte-array [1]) "audio/mpeg")
                           (byte-array [1])
                           (ByteArrayInputStream. (byte-array [1]))
                           (c/embedded-content (byte-array [1]))
                           (c/resource (byte-array [1]) "application/octet-stream" nil)
                           (c/embedded-content "Text as resource")
                           (c/resource "Text as resource" "text/plain" nil)
                           (c/embedded-content (c/resource "{\"a\": 1}" "text/json" "https://localhost/x.json"))])))

(def tool5 (server/tool "Error return tool"
                        nil
                        (server/obj-schema "Params for tool" {} [])
                        (fn [exchange _] (c/tool-error "An error happened"))))

;; List tools test
(deftest list-tools-test
  (testing "List tools"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (server/add-tool server tool1)
      (test-in/initialize s)
      (test-in/print-req stdin "tools/list" {})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {"tools" [{"name" "Sum tool",
                                       "description" "Tool that sums two numbers",
                                       "inputSchema"
                                       {"properties"
                                        {"a"
                                         {"type" "integer",
                                          "description" "First number"},
                                         "b"
                                         {"type" "integer",
                                          "description" "Second number"}},
                                        "required" ["a" "b"],
                                        "type" "object",
                                        "description" "Params for tool"}}]}
                   "id" int?}
                  (json/read-json (first (line-seq stdout)))))))

  (testing "List tools with cursor"
    (let [{:keys [stdin stdout] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (test-in/print-req stdin "tools/list" {:cursor "page-2"})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {"tools" vector?}
                   "id" int?}
                  (json/read-json (first (line-seq stdout))))))))

;; Call tool test
(deftest call-tool-test
  (testing "Call non-existent tool"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "message" string?}
                 "id" int?}
                (request-and-read "tools/call"
                                  {:name "non-existent-tool"
                                   :arguments {}}))))
  (testing "Call tool test"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (test-in/initialize s)

      (server/add-tool server tool1)
      (server/add-tool server tool2)
      (server/add-tool server tool3)
      (server/add-tool server tool4)
      (server/add-tool server tool5)
      (test-in/print-req stdin "tools/call" {:name "Sum tool"
                                             :arguments {:a 1 :b 2}})
      (test-in/print-req stdin "tools/call" {:name "Show image tool"
                                             :arguments {:funny? 1}})
      (test-in/print-req stdin "tools/call" {:name "Error tool"
                                             :arguments {}})
      (test-in/print-req stdin "tools/call" {:name "Coll return tool"
                                             :arguments {}})
      (test-in/print-req stdin "tools/call" {:name "Error return tool"
                                             :arguments {}})
      (.close stdin)
      (is (match? [{"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
                   {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
                   {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
                   {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
                   {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
                   {"id" int?
                    "jsonrpc" "2.0"
                    "result" {"content" [{"text" "3"}]
                              "isError" false}}
                   {"id" int?,
                    "jsonrpc" "2.0",
                    "result"
                    {"content"
                     [{"annotations" {"priority" 0, "audience" ["user"]}
                       "mimeType" "image/png",
                       "data"
                       "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn+AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq+wsbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj5OXm5+jp6uvs7e7v8PHy8/T19vf4+fr7/P3+/wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5/gIGCg4SFhoeIiYqLjI2Ojw=="}],
                     "isError" false}}
                   {"error" {"code" -32603
                             "message" "A"}
                    "id" int?
                    "jsonrpc" "2.0"}
                   {"id" int?,
                    "jsonrpc" "2.0",
                    "result"
                    {"content"
                     [{"text" "A", "type" "text"}
                      {"text" "C", "type" "text"}
                      {"text" "HI",
                       "annotations" {"priority" 1.5, "audience" ["user"]},
                       "type" "text"}
                      {"annotations" {"priority" 1.5, "audience" ["user" "assistant"]},
                       "type" "image", "mimeType" "image/jpeg", "data" "AQ=="}
                      {"type" "audio", "mimeType" "audio/mpeg", "data" "AQ=="}
                      {"resource" {"blob" "AQ==", "mimeType" "application/octet-stream"}, "type" "resource"}
                      {"resource" {"blob" "AQ==", "mimeType" "application/octet-stream"}, "type" "resource"}
                      {"resource" {"blob" "AQ==", "mimeType" "application/octet-stream"}, "type" "resource"}
                      {"resource" {"blob" "AQ==", "mimeType" "application/octet-stream"}, "type" "resource"}
                      {"resource" {"text" "Text as resource", "mimeType" "text/plain"}, "type" "resource"}
                      {"resource" {"text" "Text as resource", "mimeType" "text/plain"}, "type" "resource"}
                      {"resource"
                       {"uri" "https://localhost/x.json",
                        "text" "{\"a\": 1}",
                        "mimeType" "text/json"},
                       "type" "resource"}],
                     "isError" false}}
                   {"id" int?,
                    "jsonrpc" "2.0",
                    "result"
                    {"content" [{"text" "An error happened", "type" "text"}],
                     "isError" true}}]
                  (mapv json/read-json (line-seq stdout)))))))

;; Complete completion test
(deftest complete-test
  (testing "Completion request"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (server/add-completion server "ref/prompt" "test-prompt"
                             (fn [exchange name value]
                               (c/completion-resp ["AAAA" "CCC"])))
      (test-in/print-req stdin "completion/complete"
                         {:ref {:type "ref/prompt" :name "test-prompt"}
                          :argument {:name "arg1" :value "test"}})
      (test-in/print-req stdin "completion/complete"
                         {:ref {:type "ref/prompt" :name "unknown-prompt"}
                          :argument {:name "arg1" :value "test"}})
      (.close stdin)
      (is (match?
           [{"id" int?
             "jsonrpc" "2.0"
             "result" {"hasMore" false
                       "total" 2
                       "values" ["AAAA"
                                 "CCC"]}}
            {"error" {"code" -32602
                      "data" "Completion ref/prompt/unknown-prompt not found"
                      "message" "Invalid Params"}
             "id" int?
             "jsonrpc" "2.0"}] (mapv json/read-json (line-seq stdout))))))
  (testing "Completion request with defaults"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (test-in/initialize s)
      (server/add-completion server "ref/prompt" "test-prompt"
                             (fn [exchange name value]
                               (c/completion-resp ["AAAA" "CCC"])))
      (server/set-completion-handler server
                                     (fn [exchange p1 p2 p3 p4]
                                       (c/completion-resp [p1 p2 p3 p4])))
      (test-in/print-req stdin "completion/complete"
                         {:ref {:type "ref/prompt" :name "test-prompt"}
                          :argument {:name "arg1" :value "test"}})
      (test-in/print-req stdin "completion/complete"
                         {:ref {:type "ref/prompt" :name "unknown-prompt"}
                          :argument {:name "arg1" :value "test"}})
      (.close stdin)
      (is (match?
           [{"id" int?
             "jsonrpc" "2.0"
             "result" {"hasMore" false
                       "total" 2
                       "values" ["AAAA"
                                 "CCC"]}}
            {"result" {"hasMore" false
                       "total" 4
                       "values" ["ref/prompt" "unknown-prompt" "arg1" "test"]}
             "id" int?
             "jsonrpc" "2.0"}] (mapv json/read-json (line-seq stdout)))))))

;; List roots test
(deftest list-roots-test
  (testing "listing roots"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          tool (server/tool "Test"
                            "Desc"
                            (server/obj-schema "D" {} [])
                            (fn [exchange _]
                              (-> (c/list-roots exchange)
                                  (.thenApply (fn [roots]
                                                (str "Here's your roots " roots))))))]
      (server/add-tool server tool)
      (test-in/initialize s)
      (test-in/print-req stdin "tools/call" {:name "Test" :arguments {}})

      (is (match? {"id" int?
                   "jsonrpc" "2.0"
                   "method" "roots/list"}
                  (test-in/get-req-and-resp s {:roots [{:uri "file:///home/user/projects/myproject"
                                                        :name "My Project"}]})))
      (.close stdin)
      (is (match? [{"id" int?
                    "jsonrpc" "2.0"
                    "result" {"content" [{"text" "Here's your roots [{:uri \"file:///home/user/projects/myproject\", :name \"My Project\"}]"
                                          "type" "text"}]
                              "isError" false}}]
                  (mapv json/read-json (line-seq stdout))))))
  (testing "listing roots with progress callback"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          callback-results (atom [])
          tool (server/tool "Test"
                            "Desc"
                            (server/obj-schema "D" {} [])
                            (fn [exchange _]
                              (-> (c/list-roots exchange (fn [notif]
                                                           (swap! callback-results conj notif)))
                                  (.thenApply (fn [roots] (str "Here's your roots " roots))))))]
      (server/add-tool server tool)
      (test-in/initialize s)
      (test-in/print-req stdin "tools/call" {:name "Test" :arguments {}})
      (let [[list-roots-req] (read-responses stdout)
            progress-token (get-in list-roots-req ["params" "_meta" "progressToken"])]
        (is (match? {"id" int?
                     "jsonrpc" "2.0"
                     "method" "roots/list"
                     "params" {"_meta" {"progressToken" string?}}}
                    list-roots-req))
        (write-raw-message stdin
                           {:jsonrpc "2.0"
                            :method "notifications/progress"
                            :params {:progressToken progress-token
                                     :progress 25
                                     :total 100
                                     :message "starting"}})
        (write-raw-message stdin
                           {:jsonrpc "2.0"
                            :method "notifications/progress"
                            :params {:progressToken progress-token
                                     :progress 100
                                     :total 100
                                     :message "Complete!"}})
        (write-raw-message stdin
                           {:jsonrpc "2.0" :result {:roots [{:uri "file:///home/user/projects/myproject"
                                                             :name "My Project"}]} :id (get list-roots-req "id")})
        (.close stdin)
        (let [responses (read-responses stdout)]
          (is (match? [{"id" int?
                        "jsonrpc" "2.0"
                        "result" {"content" [{"text" "Here's your roots [{:uri \"file:///home/user/projects/myproject\", :name \"My Project\"}]"
                                              "type" "text"}]
                                  "isError" false}}] responses))
          (is (= [{:message "starting"
                   :progress 25
                   :progressToken progress-token
                   :total 100}
                  {:message "Complete!"
                   :progress 100
                   :progressToken progress-token
                   :total 100}]
                 @callback-results))))))
  (testing "listing roots with cancel"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          tool (server/tool "Test"
                            "Desc"
                            (server/obj-schema "D" {} [])
                            (fn [exchange _]
                              (.cancel (c/list-roots exchange) true)
                              "Whatever"))]
      (server/add-tool server tool)
      (test-in/initialize s)
      (test-in/print-req stdin "tools/call" {:name "Test" :arguments {}})
      (.close stdin)
      (let [client-req (read-responses stdout)]
        (is (match? [{"id" int?
                      "jsonrpc" "2.0"
                      "method" "roots/list"}
                     {"id" int?
                      "jsonrpc" "2.0"
                      "result" {"content" [{"text" "Whatever"
                                            "type" "text"}]
                                "isError" false}}]
                    client-req))))))

;; Logging test
(deftest logging-test
  (testing "Set log level"
    (is (match? {"jsonrpc" "2.0"
                 "result" {}
                 "id" int?}
                (request-and-read "logging/setLevel" {:level "debug"}))))

  (testing "Invalid log level"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "message" string?}
                 "id" int?}
                (request-and-read "logging/setLevel" {:level "invalid-level"})))))

;; Sampling test
(deftest sampling-test
  (testing "Create message"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          sreq (c/sampling-request "Hello" (c/model-preferences [{:name "claude-3"}] nil nil) nil nil)
          tool (server/tool "Test"
                            "Desc"
                            (server/obj-schema "D" {} [])
                            (fn [exchange _]
                              (-> (c/sampling exchange sreq)
                                  (.thenApply (fn [sampling]
                                                (str "Here's your sampling " sampling))))))]
      (server/add-tool server tool)
      (test-in/initialize s)
      (test-in/print-req stdin "tools/call" {:name "Test" :arguments {}})
      (is (match? {"id" int?
                   "jsonrpc" "2.0"
                   "method" "sampling/createMessage"
                   "params" {"messages" [{"content" {"text" "Hello" "type" "text"}}]
                             "modelPreferences" {"hints" [{"name" "claude-3"}]}}}
                  (test-in/get-req-and-resp s {:role "assistant",
                                               :content {:type "text",
                                                         :text "The capital of France is Paris."},
                                               :model "claude-3-sonnet-20240307",
                                               :stopReason "endTurn"})))
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {"content"
                             [{"text"
                               "Here's your sampling {:role \"assistant\", :content {:type \"text\", :text \"The capital of France is Paris.\"}, :model \"claude-3-sonnet-20240307\", :stopReason \"endTurn\"}",
                               "type" "text"}],
                             "isError" false}
                   "id" int?}
                  (json/read-json (first (line-seq stdout))))))))

;; Invalid method test
(deftest invalid-method-test
  (is (match? {"jsonrpc" "2.0"
               "error" {"code" -32601 ; Method not found
                        "message" string?}
               "id" int?}
              (request-and-read "invalid/method" {}))))

;; Invalid JSON-RPC test
(deftest invalid-jsonrpc-test
  (testing "Missing jsonrpc field"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (doto stdin
        (.write (json/write-json-str {:method "ping" :id 1}))
        (.write "\n")
        (.flush)
        (.close))
      (is (match? {"jsonrpc" "2.0"
                   "error" {"code" -32600 ; Invalid Request
                            "message" string?}
                   "id" (m/any-of nil? int?)}
                  (json/read-json (first (line-seq stdout)))))))
  (testing "Invalid JSON"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (doto stdin
        (.write "invalid json{")
        (.write "\n")
        (.flush)
        (.close))
      (is (match? {"jsonrpc" "2.0"
                   "error" {"code" -32700 ; Parse error
                            "message" string?}
                   "id" nil}
                  (json/read-json (first (line-seq stdout))))))))

;; Batch request test
(deftest batch-request-test
  (let [{:keys [stdin stdout]} (test-in/create-server)]
    (write-batch stdin
                 [{:jsonrpc "2.0" :method "ping" :params {} :id 1}
                  {:jsonrpc "2.0" :method "tools/list" :params {} :id 2}
                  {:jsonrpc "2.0" :method "prompts/list" :params {} :id 3}])
    (.close stdin)
    (let [responses (first (read-responses stdout))]
      (is (= 3 (count responses)))
      (is (every? #(= "2.0" (get % "jsonrpc")) responses))
      (is (= #{1 2 3} (set (map #(get % "id") responses)))))))

;; Protocol flow test
(deftest protocol-flow-test
  (testing "Complete protocol flow"
    (let [{:keys [stdin stdout server]} (test-in/create-server)]
      (add-some-resources server)
      ;; Initialize
      (test-in/print-req stdin "initialize"
                         {:protocolVersion init/server-protocol-version
                          :capabilities {}
                          :clientInfo {:name "Test Client" :version "1.0.0"}})

      ;; Send initialized notification
      (write-raw-message stdin {:jsonrpc "2.0" :method "notifications/initialized" :params {}})

      ;; Make several requests
      (test-in/print-req stdin "tools/list" {})
      (test-in/print-req stdin "prompts/list" {})
      (test-in/print-req stdin "resources/list" {})
      (test-in/print-req stdin "ping" {})

      (.close stdin)

      ;; Should get responses for all requests except notification
      (let [responses (read-responses stdout)]
        (is (= 5 (count responses)))
        (is (every? #(contains? % "result") responses))))))

;; Error handling tests
(deftest error-handling-test
  (testing "Missing required parameters"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" -32602 ; Invalid params
                          "message" string?}
                 "id" int?}
                (request-and-read "resources/read" {})))) ; Missing uri parameter

  (testing "Wrong parameter types"
    (is (match? {"jsonrpc" "2.0"
                 "error" {"code" int?
                          "message" string?}
                 "id" int?}
                (request-and-read "logging/setLevel" {:level 123}))))) ; Should be string

;; Cancelled request test
(deftest cancelled-request-test
  (testing "Tool can detect request cancellation via notifications/cancelled"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          reason (promise)
          slow-tool (server/tool "test"
                                 "Tool that runs slowly and checks for cancellation"
                                 (server/obj-schema "Parameters" {} [])
                                 (fn [exchange arguments]
                                   (.thenApply (c/req-cancelled-future exchange)
                                               #(deliver reason %))))]
      
      (server/add-tool server slow-tool)
      (test-in/initialize s)

      (let [request-id "reqid"]
        (test-in/print-req stdin "tools/call" {:name "test" :arguments {}} request-id)
        (write-raw-message stdin {:jsonrpc "2.0"
                                  :method "notifications/cancelled"
                                  :params {:requestId request-id
                                           :reason "User requested cancellation"}}))
      (.close stdin)
      (let [responses (read-responses stdout)]
        (is (= [] responses)))
      (is (= "User requested cancellation" @reason)))))

(deftest progress-notification-test
  (testing "Progress notifications during tool execution with progress token"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          progress-tool (server/tool "Progress Tool"
                                     "Tool that reports progress"
                                     (server/obj-schema "Parameters for progress tool" {} [])
                                     (fn [exchange arguments]
                                       (c/report-progress exchange {:progress 25 :total 100 :message "Starting..."})
                                       (c/report-progress exchange {:progress 50 :total 100 :message "Halfway..."})
                                       (c/report-progress exchange {:progress 100 :total 100 :message "Complete!"})
                                       "Tool execution finished"))]
      (server/add-tool server progress-tool)
      (test-in/initialize s)

      (test-in/print-req stdin "tools/call" {:name "Progress Tool"
                                             :arguments {}
                                             :_meta {:progressToken "test-progress-123"}}
                         "test-id")
      (.close stdin)
      ;; Should get progress notifications followed by tool response
      (let [responses (read-responses stdout)]
        (is (= [{"jsonrpc" "2.0"
                 "method" "notifications/progress"
                 "params" {"progressToken" "test-progress-123"
                           "progress" 25
                           "total" 100
                           "message" "Starting..."}}
                {"jsonrpc" "2.0"
                 "method" "notifications/progress"
                 "params" {"progressToken" "test-progress-123"
                           "progress" 50
                           "total" 100
                           "message" "Halfway..."}}
                {"jsonrpc" "2.0"
                 "method" "notifications/progress"
                 "params" {"progressToken" "test-progress-123"
                           "progress" 100
                           "total" 100
                           "message" "Complete!"}}
                {"jsonrpc" "2.0"
                 "result" {"content" [{"text" "Tool execution finished"
                                       "type" "text"}]
                           "isError" false}
                 "id" "test-id"}] responses)))))

  (testing "Tool execution without progress token should not send progress notifications"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)
          progress-tool (server/tool "Progress Tool"
                                     "Tool that reports progress"
                                     (server/obj-schema "Parameters for progress tool" {} [])
                                     (fn [exchange arguments]
                                       (c/report-progress exchange {:progress 50 :total 100 :message "Progress..."})
                                       (c/report-progress exchange {:progress 100 :total 100 :message "Done!"})
                                       "Tool execution finished"))]

      (server/add-tool server progress-tool)
      (test-in/initialize s)

      ;; Send tool call without progress token
      (test-in/print-req stdin "tools/call" {:name "Progress Tool" :arguments {}} "test-id")
      (.close stdin)

      ;; Should only get tool response, no progress notifications
      (is (= [{"id" "test-id"
               "jsonrpc" "2.0"
               "result" {"content" [{"text" "Tool execution finished"
                                     "type" "text"}]
                         "isError" false}}]
             (read-responses stdout))))))

;; Resource subscription test
(deftest resource-subscription-test
  (testing "Subscribe to resource updates"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (add-some-resources server)
      (test-in/initialize s)
      (server/notify-resource-changed server "http://resource.txt")
      (test-in/print-req stdin "resources/subscribe" {:uri "http://resource.txt"})
      (is (match? {"id" int?
                   "jsonrpc" "2.0"
                   "result" "http://resource.txt"}
                  (json/read-json (.readLine stdout))))
      (server/notify-resource-changed server "http://resource.txt")
      (is (= {"jsonrpc" "2.0"
              "method" "notifications/resources/updated"
              "params" {"uri" "http://resource.txt"}}
             (json/read-json (.readLine stdout))))
      (test-in/print-req stdin "resources/unsubscribe" {:uri "http://resource.txt"})
      (is (match? {"id" int?
                   "jsonrpc" "2.0"
                   "result" "http://resource.txt"}
                  (json/read-json (.readLine stdout))))
      (is (= #{} (::mcp/resource-subscriptions @server)))
      (server/notify-resource-changed server "http://resource.txt")
      (.close stdin)

      (is (= [] (mapv json/read-json (line-seq stdout)))))))

;; Multiple concurrent requests test
(deftest concurrent-requests-test
  (testing "Multiple requests sent before reading responses"
    (let [{:keys [stdin stdout server] :as s} (test-in/create-server)]
      (add-some-resources server)
      (test-in/initialize s)
      ;; Send multiple requests
      (test-in/print-req stdin "ping" {})
      (test-in/print-req stdin "tools/list" {})
      (test-in/print-req stdin "prompts/list" {})
      (test-in/print-req stdin "resources/list" {})
      (.close stdin)

      ;; Read all responses
      (let [responses (read-responses stdout)]
        (is (= 4 (count responses)))
        (is (every? #(= "2.0" (get % "jsonrpc")) responses))
        (is (every? #(contains? % "result") responses))
        ;; Check that all request IDs are present
        (let [response-ids (set (map #(get % "id") responses))]
          (is (= 4 (count response-ids))))))))

;; Server capabilities test
(deftest server-capabilities-test
  (testing "Server advertises correct capabilities"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (test-in/print-req stdin "initialize"
                         {:protocolVersion init/server-protocol-version
                          :capabilities {:tools {}
                                         :prompts {}
                                         :resources {:subscribe true}
                                         :logging {}
                                         :sampling {}}
                          :clientInfo {:name "Test Client" :version "1.0.0"}})
      (.close stdin)

      (let [response (json/read-json (first (line-seq stdout)))]
        (is (match? {"jsonrpc" "2.0"
                     "result" {"protocolVersion" init/server-protocol-version
                               "capabilities" map?
                               "serverInfo" {"name" "Test MCP Server"
                                             "version" "1.0.0"}}
                     "id" int?}
                    response))
        ;; Check specific capabilities if needed
        (when-let [caps (get-in response ["result" "capabilities"])]
          (is (map? caps)))))))

;; Edge cases test
(deftest edge-cases-test
  (testing "Empty batch request"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (doto stdin
        (.write (json/write-json-str []))
        (.write "\n")
        (.flush)
        (.close))
      (is (match? {"jsonrpc" "2.0"
                   "error" {"code" -32600 ; Invalid Request
                            "message" string?}
                   "id" nil}
                  (json/read-json (first (line-seq stdout)))))))

  (testing "Null params"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (test-in/print-req stdin "ping" nil)
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "result" {}
                   "id" int?}
                  (json/read-json (first (line-seq stdout)))))))

  (testing "Very large request ID"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (doto stdin
        (.write (json/write-json-str {:jsonrpc "2.0"
                                      :method "ping"
                                      :params {}
                                      :id 9999999999999}))
        (.write "\n")
        (.flush)
        (.close))
      (is (match? {"jsonrpc" "2.0"
                   "result" {}
                   "id" 9999999999999}
                  (json/read-json (first (line-seq stdout)))))))

  (testing "String request ID"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (doto stdin
        (.write (json/write-json-str {:jsonrpc "2.0"
                                      :method "ping"
                                      :params {}
                                      :id "string-id"}))
        (.write "\n")
        (.flush)
        (.close))
      (is (match? {"jsonrpc" "2.0"
                   "result" {}
                   "id" "string-id"}
                  (json/read-json (first (line-seq stdout))))))))

(deftest protocol-version-test
  (testing "Server accepts supported protocol version"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (test-in/print-req stdin "initialize"
                         {:protocolVersion init/server-protocol-version
                          :capabilities {}
                          :clientInfo {:name "Test Client" :version "1.0.0"}})
      (.close stdin)

      (let [response (json/read-json (first (line-seq stdout)))]
        (is (= init/server-protocol-version (get-in response ["result" "protocolVersion"]))))))

  (testing "Server rejects unsupported protocol version"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      (test-in/print-req stdin "initialize"
                         {:protocolVersion "1999-01-01"
                          :capabilities {}
                          :clientInfo {:name "Test Client" :version "1.0.0"}})
      (.close stdin)
      (is (match? {"jsonrpc" "2.0"
                   "error" {"code" int?
                            "data" #".*protocol.*version.*"}
                   "id" int?}
                  (json/read-json (first (line-seq stdout))))))))

;; Log message notification test
(deftest log-message-test
  (testing "Server can send log messages"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      ;; Set log level to debug to potentially trigger log messages
      (test-in/print-req stdin "logging/setLevel" {:level "debug"})

      ;; Make a request that might generate logs
      (test-in/print-req stdin "tools/list" {})
      (.close stdin)

      ;; Read responses - might include log notifications
      (let [responses (read-responses stdout)]
        (is (>= (count responses) 2))
        ;; At least the two request responses should be present
        (is (some #(contains? % "result") responses))))))

;; Test cleanup and resource management
(deftest cleanup-test
  (testing "Multiple server instances can be created and cleaned up"
    (let [servers (repeatedly 5 test-in/create-server)]
      (doseq [{:keys [stdin stdout]} servers]
        (test-in/print-req stdin "ping" {})
        (.close stdin)
        (is (match? {"jsonrpc" "2.0" "result" {} "id" int?}
                    (json/read-json (first (line-seq stdout)))))))))

;; Test request timeout behavior
(deftest request-timeout-test
  (testing "Server handles incomplete requests gracefully"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      ;; Send partial JSON
      (doto stdin
        (.write "{\"jsonrpc\": \"2.0\", ")
        (.flush))

      ;; Wait a bit
      (Thread/sleep 100)

      ;; Complete the request
      (doto stdin
        (.write "\"method\": \"ping\", \"params\": {}, \"id\": 1}")
        (.write "\n")
        (.flush)
        (.close))

      ;; Should still get response
      (is (match? {"jsonrpc" "2.0" "result" {} "id" 1}
                  (json/read-json (first (line-seq stdout))))))))

;; Test notification handling
(deftest notification-handling-test
  (testing "Server processes notifications without responding"
    (let [{:keys [stdin stdout]} (test-in/create-server)]
      ;; Send notification (no id field)
      (doto stdin
        (.write (json/write-json-str {:jsonrpc "2.0"
                                      :method "notifications/initialized"
                                      :params {}}))
        (.write "\n")
        (.flush))

      ;; Send a request to verify server is still working
      (test-in/print-req stdin "ping" {})
      (.close stdin)

      ;; Should only get ping response, not notification response
      (let [responses (read-responses stdout)]
        (is (= 1 (count responses)))
        (is (match? {"jsonrpc" "2.0" "result" {} "id" int?}
                    (first responses)))))))