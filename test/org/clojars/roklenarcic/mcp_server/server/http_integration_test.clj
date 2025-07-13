(ns org.clojars.roklenarcic.mcp-server.server.http-integration-test
  (:require [clojure.test :refer :all]
            [charred.api :as json]
            [clj-http.client :as client]
            [ring.adapter.jetty :as jetty]
            [org.clojars.roklenarcic.mcp-server.server.http :as http]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.handler.init :as init]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
            [matcher-combinators.test]))

(def test-port 8090)
(def base-url (str "http://localhost:" test-port))

(defn create-test-session []
  (server/make-session
    (server/server-info "Test HTTP MCP Server" "1.0.0" "Test server for HTTP integration" true)
    (charred/serde {})
    {}))

(defn start-test-server [session-template]
  (let [sessions (http/memory-sessions-store)
        handler (http/ring-handler session-template sessions 
                                   {:allowed-origins ["http://localhost:3000" "http://127.0.0.1:3000"]
                                    :client-req-timeout-ms 30000})
        server (jetty/run-jetty handler {:port test-port :join? false})]
    {:server server :sessions sessions}))

(defn stop-test-server [server-info]
  (.stop (:server server-info)))

(defn post-request 
  ([url body] (post-request url body {}))
  ([url body headers]
   (client/post url
                {:body (if (string? body) body (json/write-json-str body))
                 :headers (merge {"Content-Type" "application/json"
                                  "Origin" "http://localhost:3000"} headers)
                 :throw-exceptions false})))

(defn get-request 
  ([url] (get-request url {}))
  ([url headers]
   (client/get url {:headers (merge {"Origin" "http://localhost:3000"} headers) :throw-exceptions false})))

(defn delete-request 
  ([url] (delete-request url {}))
  ([url headers]
   (client/delete url {:headers (merge {"Origin" "http://localhost:3000"} headers) :throw-exceptions false})))

(defn initialize-session []
  (let [init-request {:jsonrpc "2.0"
                      :method "initialize"
                      :params {:protocolVersion init/server-protocol-version
                               :capabilities {}
                               :clientInfo {:name "Test HTTP Client" :version "1.0.0"}}
                      :id 1}
        response (post-request base-url init-request)
        session-id (get-in response [:headers "mcp-session-id"])]

    (post-request base-url {:jsonrpc "2.0"
                            :method "notifications/initialized"
                            :params {}}
                  {"Mcp-Session-Id" session-id})
    session-id))

(deftest http-initialization-test
  (testing "HTTP server initialization flow"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]

      (try
        ;; Initialize a new session
        (let [init-request {:jsonrpc "2.0"
                            :method "initialize"
                            :params {:protocolVersion init/server-protocol-version
                                     :capabilities {}
                                     :clientInfo {:name "Test HTTP Client" :version "1.0.0"}}
                            :id 1}
              response (post-request base-url init-request)]

          (is (= 200 (:status response)))
          (is (get-in response [:headers "mcp-session-id"]))

          (let [body (json/read-json (:body response))
                session-id (get-in response [:headers "mcp-session-id"])]
            (is (match? {"jsonrpc" "2.0"
                         "result" {"protocolVersion" init/server-protocol-version
                                   "capabilities" map?
                                   "serverInfo" map?}
                         "id" 1}
                        body))

            ;; Test that session was created
            (is (some? (http/get-session (:sessions server-info) session-id)))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-session-management-test
  (testing "HTTP session lifecycle"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]

      (try
        ;; Test ping with session
        (let [ping-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "ping"
                                           :params {}
                                           :id 2}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status ping-response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {}
                       "id" 2}
                      (json/read-json (subs (:body ping-response) 5)))))

        ;; Delete session
        (let [delete-response (delete-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status delete-response))))

        ;; Verify session is gone
        (let [ping-after-delete (post-request base-url
                                              {:jsonrpc "2.0"
                                               :method "ping"
                                               :params {}
                                               :id 3}
                                              {"Mcp-Session-Id" session-id})]
          (is (= 404 (:status ping-after-delete))))

        (finally
          (stop-test-server server-info))))))

(deftest http-cors-test
  (testing "CORS handling"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      
      (try
        ;; Test allowed origin
        (let [response (post-request base-url 
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion init/server-protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "Test Client" :version "1.0.0"}}
                                      :id 1}
                                     {"Origin" "http://localhost:3000"})]
          (is (= 200 (:status response))))
        
        ;; Test disallowed origin
        (let [response (post-request base-url 
                                     {:jsonrpc "2.0"
                                      :method "ping"
                                      :params {}
                                      :id 1}
                                     {"Origin" "http://evil.com"})]
          (is (= 403 (:status response))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-tools-integration-test
  (testing "Tools over HTTP"
    (let [session-template (create-test-session)
          calculator-tool (server/tool "calculator"
                                       "Simple calculator"
                                       (server/obj-schema "Calculator params"
                                                          {:operation (server/str-schema "Operation" nil)
                                                           :a (server/num-schema "First number")
                                                           :b (server/num-schema "Second number")}
                                                          ["operation" "a" "b"])
                                       (fn [exchange params]
                                         (case (:operation params)
                                           "add" (+ (:a params) (:b params))
                                           "multiply" (* (:a params) (:b params)))))
          _ (server/add-tool session-template calculator-tool)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        ;; List tools
        (let [list-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "tools/list"
                                           :params {}
                                           :id 2}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status list-response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"tools" [{"name" "calculator"
                                           "description" "Simple calculator"
                                           "inputSchema" map?}]}
                       "id" 2}
                      (json/read-json (subs (:body list-response) 5)))))

        ;; Call tool
        (let [call-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "tools/call"
                                           :params {:name "calculator"
                                                    :arguments {:operation "add"
                                                                :a 5
                                                                :b 3}}
                                           :id 3}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status call-response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"content" [{"type" "text"
                                             "text" "8"}]
                                 "isError" false}
                       "id" 3}
                      (json/read-json (subs (:body call-response) 5)))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-resources-integration-test
  (testing "Resources over HTTP"
    (let [session-template (create-test-session)
          _ (server/set-resources-handler session-template (lookup/lookup-map true))
          _ (lookup/add-resource
              session-template
              (c/resource-desc "file:///test.txt" "Test File" "A test file" "text/plain" nil)
              (fn [uri params] "Hello from file!"))
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        ;; List resources
        (let [list-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "resources/list"
                                           :params {}
                                           :id 2}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status list-response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"resources" [{"uri" "file:///test.txt"
                                               "name" "Test File"
                                               "description" "A test file"
                                               "mimeType" "text/plain"}]}
                       "id" 2}
                      (json/read-json (subs (:body list-response) 5)))))

        ;; Read resource
        (let [read-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "resources/read"
                                           :params {:uri "file:///test.txt"}
                                           :id 3}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status read-response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"contents" [{"uri" "file:///test.txt"
                                              "text" "Hello from file!"
                                              "mimeType" "text/plain"}]}
                       "id" 3}
                      (json/read-json (subs (:body read-response) 5)))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-sse-test
  (testing "Server-Sent Events"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]

      (try
        ;; Initialize session
        ;; Test SSE connection
        (let [sse-response (get-request (str base-url "?sessionId=" session-id))]
          (is (= 200 (:status sse-response)))
          (is (= "text/event-stream" (get-in sse-response [:headers "content-type"]))))

        ;; Test POST with SSE response
        (let [post-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "ping"
                                           :params {}
                                           :id 2}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status post-response)))
          (is (= "text/event-stream" (get-in post-response [:headers "content-type"]))))

        (finally
          (stop-test-server server-info))))))

(deftest http-batch-requests-test
  (testing "Batch requests over HTTP"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      
      (try
        ;; Send batch request
        (let [batch-request [{:jsonrpc "2.0" :method "ping" :params {} :id 1}
                             {:jsonrpc "2.0" :method "tools/list" :params {} :id 2}
                             {:jsonrpc "2.0" :method "prompts/list" :params {} :id 3}]
              batch-response (post-request base-url
                                           batch-request
                                           {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status batch-response)))
          (let [response-body (json/read-json (subs (:body batch-response) 5))]
            (is (vector? response-body))
            (is (= 3 (count response-body)))
            (is (every? #(= "2.0" (get % "jsonrpc")) response-body))
            (is (= #{1 2 3} (set (map #(get % "id") response-body))))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-error-handling-test
  (testing "HTTP error responses"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      
      (try
        ;; Test request without session
        (let [response (post-request base-url {:jsonrpc "2.0" :method "ping" :params {} :id 1})]
          ;; Should try to initialize since no session provided
          (is (= 400 (:status response))))
        
        ;; Test invalid JSON
        (let [response (post-request base-url "invalid json{")]
          (is (= 400 (:status response))))
        
        ;; Test with invalid session ID
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 1}
                                     {"Mcp-Session-Id" "invalid-session-id"})]
          (is (= 404 (:status response))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-concurrent-sessions-test
  (testing "Multiple concurrent HTTP sessions"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      
      (try
        ;; Create multiple sessions
        (let [session1-id (initialize-session)
              session2-id (initialize-session)]
          
          ;; Verify sessions are different
          (is (not= session1-id session2-id))
          
          ;; Test both sessions work independently
          (let [ping1-response (post-request base-url 
                                             {:jsonrpc "2.0"
                                              :method "ping"
                                              :params {}
                                              :id 2}
                                             {"Mcp-Session-Id" session1-id})
                ping2-response (post-request base-url 
                                             {:jsonrpc "2.0"
                                              :method "ping"
                                              :params {}
                                              :id 3}
                                             {"Mcp-Session-Id" session2-id})]
            (is (= 200 (:status ping1-response)))
            (is (= 200 (:status ping2-response)))
            (is (match? {"jsonrpc" "2.0" "result" {} "id" 2}
                        (json/read-json (subs (:body ping1-response) 5))))
            (is (match? {"jsonrpc" "2.0" "result" {} "id" 3}
                        (json/read-json (subs (:body ping2-response) 5))))))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-session-cleanup-test
  (testing "Session cleanup and timeout"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      
      (try
        ;; Verify session exists
        (is (some? (http/get-session (:sessions server-info) session-id)))

        ;; Delete session
        (let [delete-response (delete-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status delete-response))))

        ;; Verify session is removed
        (is (nil? (http/get-session (:sessions server-info) session-id)))
        
        (finally
          (stop-test-server server-info))))))

(deftest http-protocol-version-test
  (testing "Protocol version validation over HTTP"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      
      (try
        ;; Test supported version
        (let [response (post-request base-url 
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion init/server-protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "Test Client" :version "1.0.0"}}
                                      :id 1})]
          (is (= 200 (:status response)))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"protocolVersion" init/server-protocol-version}
                       "id" 1}
                      (json/read-json (:body response)))))
        
        ;; Test unsupported version
        (let [response (post-request base-url 
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion "1999-01-01"
                                               :capabilities {}
                                               :clientInfo {:name "Test Client" :version "1.0.0"}}
                                      :id 2})]
          (is (= 200 (:status response)))
          (is (match? {"jsonrpc" "2.0"
                       "error" {"code" int?
                                "message" string?}
                       "id" 2}
                      (json/read-json (:body response)))))
        
        (finally
          (stop-test-server server-info))))))