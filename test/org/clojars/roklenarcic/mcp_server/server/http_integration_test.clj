(ns org.clojars.roklenarcic.mcp-server.server.http-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [charred.api :as json]
            [clj-http.client :as client]
            [ring.adapter.jetty :as jetty]
            [org.clojars.roklenarcic.mcp-server.server.http :as http]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.init :as init]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
            [matcher-combinators.test])
  (:import (java.util.concurrent CompletableFuture CountDownLatch Executors TimeUnit)))

(def test-port 8091)
(def base-url (str "http://localhost:" test-port))

(def origin "http://localhost:3000")
(def post-accept "application/json, text/event-stream")
(def get-accept "text/event-stream")

(defn create-test-session []
  (server/make-session
    (server/server-info "Test HTTP MCP Server" "1.0.0" "Test server for HTTP integration" true)
    (charred/serde {})
    {}))

(defn start-test-server
  ([session-template] (start-test-server session-template {}))
  ([session-template {:keys [async?] :or {async? false}}]
   (let [sessions (http/memory-sessions-store)
         handler (http/ring-handler session-template sessions
                                    {:allowed-origins ["http://localhost:3000" "http://127.0.0.1:3000"]
                                     :client-req-timeout-ms 30000})
         server (jetty/run-jetty handler {:port test-port :join? false :async? async?})]
     {:server server :sessions sessions})))

(defn stop-test-server [server-info]
  (.stop (:server server-info)))

(defn post-request
  ([url body] (post-request url body {}))
  ([url body headers]
   (client/post url
                {:body (if (string? body) body (json/write-json-str body))
                 :headers (merge {"Content-Type" "application/json"
                                  "Accept" post-accept
                                  "Origin" origin}
                                 headers)
                 :throw-exceptions false})))

(defn get-request
  ([url] (get-request url {}))
  ([url headers]
   (client/get url {:headers (merge {"Accept" get-accept
                                     "Origin" origin}
                                    headers)
                    :throw-exceptions false
                    :socket-timeout 2000
                    :connection-timeout 2000})))

(defn- parse-sse-events
  "Parses an SSE response body into a vector of `{:event-id :data}` maps.

  Each event has the shape `id: <event-id>\\ndata: <json>\\n\\n`; we split
  on the SSE event terminator, pull the `id:` and `data:` lines, parse
  the data payload via charred, and parse the id as a Long. Keys come
  back as strings to match the other tests in this namespace."
  [body]
  (->> (str/split body #"\n\n")
       (remove str/blank?)
       (mapv (fn [event]
               (let [lines (str/split-lines event)
                     id-line (some #(when (str/starts-with? % "id: ") %) lines)
                     data-line (some #(when (str/starts-with? % "data: ") %) lines)]
                 {:event-id (when id-line (Long/parseLong (subs id-line (count "id: "))))
                  :data (json/read-json (subs data-line (count "data: ")))})))))

(defn- parse-sse-body
  "Parses an SSE response body into a vector of JSON-decoded events (data only).

  See `parse-sse-events` for a variant that also returns the SSE event id."
  [body]
  (mapv :data (parse-sse-events body)))

(defn delete-request
  ([url] (delete-request url {}))
  ([url headers]
   (client/delete url {:headers (merge {"Origin" origin} headers)
                       :throw-exceptions false})))

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
  (testing "POST /mcp without Mcp-Session-Id and a valid initialize request creates a session"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion init/server-protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "Test HTTP Client" :version "1.0.0"}}
                                      :id 1})
              session-id (get-in response [:headers "mcp-session-id"])]
          (is (= 200 (:status response)))
          (is (= "application/json" (get-in response [:headers "content-type"])))
          (is (some? session-id))
          (is (match? {"jsonrpc" "2.0"
                       "result" {"protocolVersion" init/server-protocol-version
                                 "capabilities" map?
                                 "serverInfo" map?}
                       "id" 1}
                      (json/read-json (:body response))))
          (is (some? (http/get-session (:sessions server-info) session-id))))
        (finally
          (stop-test-server server-info))))))

(deftest http-session-management-test
  (testing "Session lifecycle: ping, delete, ping after delete returns 404"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 2}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0" "result" {} "id" 2}]
                      (parse-sse-body (:body response)))))

        (let [response (delete-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response))))

        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 3}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 404 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-cors-test
  (testing "Origin allowlist blocks unknown origins"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion init/server-protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "Test Client" :version "1.0.0"}}
                                      :id 1}
                                     {"Origin" origin})]
          (is (= 200 (:status response))))

        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 1}
                                     {"Origin" "http://evil.com"})]
          (is (= 403 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-tools-integration-test
  (testing "Tool listing and invocation over HTTP return SSE-wrapped JSON bodies"
    (let [session-template (create-test-session)
          calculator-tool (server/tool "calculator"
                                       "Simple calculator"
                                       (server/obj-schema "Calculator params"
                                                          {:operation (server/str-schema "Operation" nil)
                                                           :a (server/num-schema "First number")
                                                           :b (server/num-schema "Second number")}
                                                          ["operation" "a" "b"])
                                       (fn [_ params]
                                         (case (:operation params)
                                           "add" (+ (:a params) (:b params))
                                           "multiply" (* (:a params) (:b params)))))
          _ (server/add-tool session-template calculator-tool)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "tools/list" :params {} :id 2}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0"
                        "result" {"tools" [{"name" "calculator"
                                            "description" "Simple calculator"
                                            "inputSchema" map?}]}
                        "id" 2}]
                      (parse-sse-body (:body response)))))

        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "tools/call"
                                      :params {:name "calculator"
                                               :arguments {:operation "add" :a 5 :b 3}}
                                      :id 3}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0"
                        "result" {"content" [{"type" "text" "text" "8"}]
                                  "isError" false}
                        "id" 3}]
                      (parse-sse-body (:body response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-post-sse-piggyback-notification-test
  (testing "A tool handler that sends a notification mid-dispatch piggybacks it on the POST-SSE response stream"
    (let [session-template (create-test-session)
          notify-tool (server/tool "notify-then-reply"
                                   "Sends a notification and replies"
                                   (server/obj-schema "params" {} [])
                                   (fn [exchange _]
                                     (rpc/send-notification
                                       (c/get-session exchange)
                                       "notifications/message"
                                       {:level "info" :data "piggyback"})
                                     "ok"))
          _ (server/add-tool session-template notify-tool)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "tools/call"
                                      :params {:name "notify-then-reply"
                                               :arguments {}}
                                      :id 7}
                                     {"Mcp-Session-Id" session-id})
              events (parse-sse-events (:body response))
              event-ids (mapv :event-id events)]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (= 2 (count events))
              "Notification (piggybacked) and JSON-RPC response should arrive as two SSE events")
          (is (= event-ids (sort event-ids))
              "Event IDs must be strictly monotonic across notification + response")
          (is (apply < event-ids)
              "Event IDs must be strictly increasing")
          (is (match? [{"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "piggyback"}}
                       {"jsonrpc" "2.0"
                        "result" {"content" [{"type" "text" "text" "ok"}]
                                  "isError" false}
                        "id" 7}]
                      (mapv :data events))
              "Notification must be delivered before the response on the POST-SSE stream"))
        (finally
          (stop-test-server server-info))))))

(deftest http-post-sse-cross-request-isolation-test
  (testing "Notifications produced by concurrently in-flight POST handlers
route to the originating request's POST-SSE response stream, not to a
sibling request's stream. A CountDownLatch forces the two handlers to
overlap so both POST streams are simultaneously registered when each
notification is sent."
    (let [latch (CountDownLatch. 2)
          session-template (create-test-session)
          tag-tool (server/tool "tag-notify"
                                "Sends a tagged notification then awaits the sibling before replying"
                                (server/obj-schema "params"
                                                   {:tag (server/str-schema "tag" nil)}
                                                   ["tag"])
                                (fn [exchange params]
                                  (let [tag (:tag params)]
                                    (rpc/send-notification
                                      (c/get-session exchange)
                                      "notifications/message"
                                      {:level "info" :data tag})
                                    (.countDown latch)
                                    (.await latch 5 TimeUnit/SECONDS)
                                    tag)))
          _ (server/add-tool session-template tag-tool)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [send-post (fn [tag req-id]
                          (future
                            (post-request base-url
                                          {:jsonrpc "2.0"
                                           :method "tools/call"
                                           :params {:name "tag-notify"
                                                    :arguments {:tag tag}}
                                           :id req-id}
                                          {"Mcp-Session-Id" session-id})))
              f-a (send-post "A" 100)
              f-b (send-post "B" 101)
              r-a @f-a
              r-b @f-b
              events-a (parse-sse-events (:body r-a))
              events-b (parse-sse-events (:body r-b))
              event-text (fn [e]
                           (or (get-in e [:data "params" "data"])
                               (get-in e [:data "result" "content" 0 "text"])))]
          (is (= 200 (:status r-a)))
          (is (= 200 (:status r-b)))
          (is (= "text/event-stream" (get-in r-a [:headers "content-type"])))
          (is (= "text/event-stream" (get-in r-b [:headers "content-type"])))
          (is (= 2 (count events-a))
              "Request A's stream carries exactly its own notification + response")
          (is (= 2 (count events-b))
              "Request B's stream carries exactly its own notification + response")
          (is (every? #(= "A" (event-text %)) events-a)
              "Every event on request A's stream references tag A — no cross-talk from B")
          (is (every? #(= "B" (event-text %)) events-b)
              "Every event on request B's stream references tag B — no cross-talk from A")
          (is (apply < (mapv :event-id events-a))
              "Event IDs on stream A are strictly increasing")
          (is (apply < (mapv :event-id events-b))
              "Event IDs on stream B are strictly increasing")
          (is (match? [{"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "A"}}
                       {"jsonrpc" "2.0"
                        "result" {"content" [{"type" "text" "text" "A"}]
                                  "isError" false}
                        "id" 100}]
                      (mapv :data events-a))
              "Stream A delivers notification before response, both tagged A")
          (is (match? [{"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "B"}}
                       {"jsonrpc" "2.0"
                        "result" {"content" [{"type" "text" "text" "B"}]
                                  "isError" false}
                        "id" 101}]
                      (mapv :data events-b))
              "Stream B delivers notification before response, both tagged B"))
        (finally
          (stop-test-server server-info))))))

(defn- read-until-double-crlf
  "Reads bytes from `in` until the sequence \\r\\n\\r\\n (end-of-headers) is
  found. Returns the header block (including the terminator) as a String.
  Throws if the connection is closed before the terminator is found."
  [^java.io.InputStream in]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (loop [state 0]
      ;; State machine: 0=idle 1=\\r 2=\\r\\n 3=\\r\\n\\r 4=done
      (let [b (.read in)]
        (when (neg? b)
          (throw (ex-info "Connection closed before end of HTTP headers" {})))
        (.write baos b)
        (let [next (case state
                     0 (if (= b 13) 1 0)
                     1 (if (= b 10) 2 (if (= b 13) 1 0))
                     2 (if (= b 13) 3 0)
                     3 (if (= b 10) 4 (if (= b 13) 1 0))
                     4 4)]
          (if (= next 4)
            (String. (.toByteArray baos) "UTF-8")
            (recur next)))))))

(deftest http-post-sse-flush-commits-headers-before-handler-test
  (testing "Initial flush() in post-sse-resp commits 200+SSE headers to the client
before any handler code runs. Verified with a raw socket: we can read the HTTP
status line and headers while the handler is still blocked on a promise latch,
proving that channelWrite(EMPTY_BUFFER, last=false) fires the TCP send synchronously
before dispatch() is invoked."
    (let [handler-started  (promise)
          handler-finished (promise)
          handler-unblock  (promise)
          session-template (create-test-session)
          _ (server/add-tool session-template
                             (server/tool "slow-tool"
                                         "blocks until released"
                                         (server/obj-schema "params" {} [])
                                         (fn [_ _]
                                           (deliver handler-started true)
                                           (deref handler-unblock 5000 nil)
                                           (deliver handler-finished true)
                                           "done")))
          server-info (start-test-server session-template {:async? true})
          session-id (initialize-session)]
      (try
        (with-open [sock (java.net.Socket. "localhost" test-port)]
          (.setSoTimeout sock 5000)
          (let [sock-in  (.getInputStream sock)
                sock-out (.getOutputStream sock)
                body-json (json/write-json-str
                            {:jsonrpc "2.0" :id 42
                             :method "tools/call"
                             :params {:name "slow-tool" :arguments {}}})
                body-bytes (.getBytes ^String body-json "UTF-8")
                raw-req (str "POST / HTTP/1.1\r\n"
                             "Host: localhost:" test-port "\r\n"
                             "Origin: " origin "\r\n"
                             "Mcp-Session-Id: " session-id "\r\n"
                             "Content-Type: application/json\r\n"
                             "Accept: " post-accept "\r\n"
                             "Content-Length: " (count body-bytes) "\r\n"
                             "Connection: close\r\n"
                             "\r\n"
                             body-json)]
            (.write sock-out (.getBytes ^String raw-req "UTF-8"))
            (.flush sock-out)
            ;; Read exactly the header block (up to and including \r\n\r\n).
            (let [header-block (read-until-double-crlf sock-in)
                  lines (str/split-lines header-block)
                  status-line (first lines)
                  header-map (into {}
                               (for [line (rest lines)
                                     :when (str/includes? line ":")
                                     :let [[k v] (str/split line #":\s+" 2)]]
                                 [(str/lower-case (str/trim k)) (str/trim v)]))]
              (is (str/starts-with? status-line "HTTP/1.1 200")
                  "Server responded 200 before handler finished")
              (is (str/starts-with? (get header-map "content-type" "") "text/event-stream")
                  "text/event-stream header arrived before any body was written")
              ;; Handler must have been dispatched (async) but MUST NOT have
              ;; finished yet — it blocks on handler-unblock which we have not
              ;; delivered. If handler-finished were already realized here, the
              ;; test would be vacuously true (flush wouldn't matter).
              (is (deref handler-started 2000 false)
                  "Handler was dispatched within 2s")
              (is (not (realized? handler-finished))
                  "Handler is still blocking: headers arrived independently of the body write — flush() works"))
            ;; Release the handler so the server can finish and the test can exit.
            (deliver handler-unblock true)
            (is (deref handler-finished 2000 false) "Handler completes after unblock")))
        (finally
          (stop-test-server server-info))))))

(deftest http-post-sse-async-completable-future-test
  (testing "POST request with a handler that returns a CompletableFuture is
dispatched without blocking the Ring thread (async adapter): post-sse-resp
attaches a .thenAccept callback that writes the response to the SSE
stream once the future completes on a different thread. Also verifies
that a notification sent synchronously by the handler before it returns
the future still routes to the originating POST-SSE stream via the
*post-stream* binding."
    (let [executor (Executors/newSingleThreadExecutor)
          completion-thread (atom nil)
          dispatch-thread (atom nil)
          session-template (create-test-session)
          async-tool (server/tool "async-notify-then-reply"
                                  "Sends a notification, then completes its response on a worker executor"
                                  (server/obj-schema "params"
                                                     {:msg (server/str-schema "msg" nil)}
                                                     ["msg"])
                                  (fn [exchange params]
                                    (reset! dispatch-thread (.getName (Thread/currentThread)))
                                    ;; Synchronous notification — must route to the
                                    ;; POST-SSE stream via the *post-stream* binding
                                    ;; established by post-sse-resp.
                                    (rpc/send-notification
                                      (c/get-session exchange)
                                      "notifications/message"
                                      {:level "info" :data (str "starting " (:msg params))})
                                    ;; Return a CompletableFuture that completes
                                    ;; on the worker executor — exercises the
                                    ;; .thenAccept (non-blocking) branch in
                                    ;; post-sse-resp.
                                    (CompletableFuture/supplyAsync
                                      (reify java.util.function.Supplier
                                        (get [_]
                                          (Thread/sleep 50)
                                          (reset! completion-thread
                                                  (.getName (Thread/currentThread)))
                                          (:msg params)))
                                      executor)))
          _ (server/add-tool session-template async-tool)
          server-info (start-test-server session-template {:async? true})
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "tools/call"
                                      :params {:name "async-notify-then-reply"
                                               :arguments {:msg "hello"}}
                                      :id 200}
                                     {"Mcp-Session-Id" session-id})
              events (parse-sse-events (:body response))]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "starting hello"}}
                       {"jsonrpc" "2.0"
                        "result" {"content" [{"type" "text" "text" "hello"}]
                                  "isError" false}
                        "id" 200}]
                      (mapv :data events))
              "Sync notification and async response both delivered on the POST-SSE stream in order")
          (is (apply < (mapv :event-id events))
              "Event IDs strictly increasing across notification + async response")
          (is (some? @dispatch-thread) "Dispatch thread recorded")
          (is (some? @completion-thread) "Completion thread recorded")
          (is (not= @dispatch-thread @completion-thread)
              "The CompletableFuture completed on the worker executor, not on the dispatch thread — proves the response write went through the .thenAccept callback rather than a blocking deref"))
        (finally
          (stop-test-server server-info)
          (.shutdown executor))))))

(deftest http-resources-integration-test
  (testing "Resource listing and reading over HTTP return SSE-wrapped JSON bodies"
    (let [session-template (create-test-session)
          _ (server/set-resources-handler session-template (lookup/lookup-map true))
          _ (lookup/add-resource
              session-template
              (c/resource-desc "file:///test.txt" "Test File" "A test file" "text/plain" nil)
              (fn [_ _] "Hello from file!"))
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "resources/list" :params {} :id 2}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0"
                        "result" {"resources" [{"uri" "file:///test.txt"
                                                "name" "Test File"
                                                "description" "A test file"
                                                "mimeType" "text/plain"}]}
                        "id" 2}]
                      (parse-sse-body (:body response)))))

        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "resources/read"
                                      :params {:uri "file:///test.txt"}
                                      :id 3}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (match? [{"jsonrpc" "2.0"
                        "result" {"contents" [{"uri" "file:///test.txt"
                                               "text" "Hello from file!"
                                               "mimeType" "text/plain"}]}
                        "id" 3}]
                      (parse-sse-body (:body response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-notification-returns-202-test
  (testing "Notifications and client responses over POST return 202 with no body"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "notifications/initialized"
                                      :params {}}
                                     {"Mcp-Session-Id" session-id})]
          (is (= 202 (:status response)))
          (is (or (nil? (:body response)) (= "" (:body response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-sse-test
  (testing "GET /mcp with valid session returns text/event-stream"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (get-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"]))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-sse-queued-delivery-test
  (testing "Server-to-client notifications queued before GET are delivered on connect"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)
          session (http/get-session (:sessions server-info) session-id)]
      (try
         ;; Queue two notifications while no SSE stream is attached.
         (rpc/send-notification (:session session) "notifications/message"
                                {:level "info" :data "hello"})
         (rpc/send-notification (:session session) "notifications/message"
                                {:level "info" :data "world"})
        (let [response (get-request base-url {"Mcp-Session-Id" session-id})
              messages (parse-sse-body (:body response))]
          (is (= 200 (:status response)))
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (= 2 (count messages)))
          (is (match? [{"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "hello"}}
                       {"jsonrpc" "2.0"
                        "method" "notifications/message"
                        "params" {"level" "info" "data" "world"}}]
                      messages)))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-sse-server-request-response-test
  (testing "Server-initiated request via SSE + client POST response completes the pending future"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)
          session (http/get-session (:sessions server-info) session-id)]
      (try
         (let [fut (rpc/send-request (:session session) "sampling/createMessage"
                                    {:question "hi"} (fn [_]))
              get-response (get-request base-url {"Mcp-Session-Id" session-id})
              messages (parse-sse-body (:body get-response))
              server-req (first messages)
              req-id (get server-req "id")
              post-response (post-request base-url
                                          {:jsonrpc "2.0"
                                           :id req-id
                                           :result {:answer "yo"}}
                                          {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status get-response)))
          (is (= 1 (count messages)))
          (is (match? {"jsonrpc" "2.0"
                       "method" "sampling/createMessage"
                       "params" {"question" "hi"}
                       "id" req-id}
                      server-req))
          (is (= 202 (:status post-response)))
          (is (.isDone fut))
          (is (= {:answer "yo"} (.get fut 1 TimeUnit/SECONDS))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-sse-cross-session-isolation-test
  (testing "Client response delivered on the wrong session never completes the originating future"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-a-id (initialize-session)
          session-b-id (initialize-session)
          session-a (http/get-session (:sessions server-info) session-a-id)]
      (try
         (let [fut-a (rpc/send-request (:session session-a) "sampling/createMessage"
                                      {:question "a"} (fn [_]))
              get-a (get-request base-url {"Mcp-Session-Id" session-a-id})
              messages-a (parse-sse-body (:body get-a))
              req-id (get (first messages-a) "id")
              ;; Send a response with id=req-id on session B; must be silently
              ;; ignored because the composite pending key differs.
              wrong-response (post-request base-url
                                           {:jsonrpc "2.0"
                                            :id req-id
                                            :result {:answer "wrong"}}
                                           {"Mcp-Session-Id" session-b-id})]
          (is (= 1 (count messages-a)))
          (is (= 202 (:status wrong-response)))
          (is (not (.isDone fut-a))
              "Future on session A must NOT complete from session B response")
          ;; Now deliver the response on the originating session.
          (let [right-response (post-request base-url
                                             {:jsonrpc "2.0"
                                              :id req-id
                                              :result {:answer "right"}}
                                             {"Mcp-Session-Id" session-a-id})]
            (is (= 202 (:status right-response)))
            (is (.isDone fut-a))
            (is (= {:answer "right"} (.get fut-a 1 TimeUnit/SECONDS)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-sse-last-event-id-replay-test
  (testing "GET reconnect with Last-Event-ID replays only events newer than the supplied id"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)
          session (http/get-session (:sessions server-info) session-id)]
      (try
         ;; Queue three notifications before any SSE stream is attached.
         (rpc/send-notification (:session session) "notifications/message" {:level "info" :data "a"})
         (rpc/send-notification (:session session) "notifications/message" {:level "info" :data "b"})
         (rpc/send-notification (:session session) "notifications/message" {:level "info" :data "c"})
        (let [first-resp (get-request base-url {"Mcp-Session-Id" session-id})
              first-events (parse-sse-events (:body first-resp))]
          (is (= 200 (:status first-resp)))
          (is (= 3 (count first-events)))
          (is (= [1 2 3] (mapv :event-id first-events)))
          ;; In sync Ring mode the previous GET detaches its stream on
          ;; return, so the next notification is queued.
           (rpc/send-notification (:session session) "notifications/message" {:level "info" :data "d"})
          (let [second-resp (get-request base-url {"Mcp-Session-Id" session-id
                                                   "Last-Event-ID" "2"})
                second-events (parse-sse-events (:body second-resp))]
            (is (= 200 (:status second-resp)))
            ;; Replay buffer yields event 3 (eid > 2); queue drain yields
            ;; event 4 from the notification queued after the disconnect.
            (is (= [3 4] (mapv :event-id second-events)))
            (is (match? [{"jsonrpc" "2.0"
                          "method" "notifications/message"
                          "params" {"level" "info" "data" "c"}}
                         {"jsonrpc" "2.0"
                          "method" "notifications/message"
                          "params" {"level" "info" "data" "d"}}]
                        (mapv :data second-events)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-without-session-test
  (testing "GET /mcp without Mcp-Session-Id returns 400"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [response (get-request base-url)]
          (is (= 400 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-unknown-session-test
  (testing "GET /mcp with unknown session id returns 404"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [response (get-request base-url {"Mcp-Session-Id" "not-a-real-session"})]
          (is (= 404 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-get-wrong-accept-test
  (testing "GET /mcp without text/event-stream in Accept returns 406"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (get-request base-url {"Mcp-Session-Id" session-id
                                              "Accept" "application/json"})]
          (is (= 406 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-post-wrong-content-type-test
  (testing "POST /mcp without application/json Content-Type returns 400"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 9}
                                     {"Mcp-Session-Id" session-id
                                      "Content-Type" "text/plain"})]
          (is (= 400 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-post-wrong-accept-test
  (testing "POST /mcp without both required media types in Accept returns 406"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0" :method "ping" :params {} :id 9}
                                     {"Mcp-Session-Id" session-id
                                      "Accept" "application/json"})]
          (is (= 406 (:status response))))
        (finally
          (stop-test-server server-info))))))

(deftest http-batch-requests-test
  (testing "JSON-RPC batches are rejected with 400 + Invalid Request error"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (let [batch [{:jsonrpc "2.0" :method "ping" :params {} :id 1}
                     {:jsonrpc "2.0" :method "tools/list" :params {} :id 2}]
              response (post-request base-url batch {"Mcp-Session-Id" session-id})]
          (is (= 400 (:status response)))
          (is (= "application/json" (get-in response [:headers "content-type"])))
          (is (match? {"jsonrpc" "2.0"
                       "error" {"code" -32600
                                "message" "Invalid Request"
                                "data" "Batch requests are not supported"}
                       "id" nil}
                      (json/read-json (:body response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-error-handling-test
  (testing "Errors map to the correct HTTP status codes"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (testing "POST without session header and not initialize -> 400"
          (let [response (post-request base-url
                                       {:jsonrpc "2.0" :method "ping" :params {} :id 1})]
            (is (= 400 (:status response)))))

        (testing "POST without session header and invalid JSON -> 400"
          (let [response (post-request base-url "invalid json{")]
            (is (= 400 (:status response)))))

        (testing "POST with unknown session id -> 404"
          (let [response (post-request base-url
                                       {:jsonrpc "2.0" :method "ping" :params {} :id 1}
                                       {"Mcp-Session-Id" "invalid-session-id"})]
            (is (= 404 (:status response)))))

        (testing "Unsupported HTTP method -> 405"
          (let [response (client/put base-url
                                     {:body "{}"
                                      :headers {"Origin" origin
                                                "Content-Type" "application/json"}
                                      :throw-exceptions false})]
            (is (= 405 (:status response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-protocol-version-header-test
  (testing "MCP-Protocol-Version header validation"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (testing "Matching version is accepted (response arrives as SSE)"
          (let [response (post-request base-url
                                       {:jsonrpc "2.0" :method "ping" :params {} :id 1}
                                       {"Mcp-Session-Id" session-id
                                        "MCP-Protocol-Version" init/server-protocol-version})]
            (is (= 200 (:status response)))
            (is (= "text/event-stream" (get-in response [:headers "content-type"])))
            (is (match? [{"jsonrpc" "2.0" "result" {} "id" 1}]
                        (parse-sse-body (:body response))))))

        (testing "Mismatched version is rejected with 400 (plain JSON error response)"
          (let [response (post-request base-url
                                       {:jsonrpc "2.0" :method "ping" :params {} :id 2}
                                       {"Mcp-Session-Id" session-id
                                        "MCP-Protocol-Version" "1999-01-01"})]
            (is (= 400 (:status response)))))
        (finally
          (stop-test-server server-info))))))

(deftest http-concurrent-sessions-test
  (testing "Multiple concurrent sessions remain isolated"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [session1-id (initialize-session)
              session2-id (initialize-session)]
          (is (not= session1-id session2-id))

          (let [r1 (post-request base-url
                                 {:jsonrpc "2.0" :method "ping" :params {} :id 2}
                                 {"Mcp-Session-Id" session1-id})
                r2 (post-request base-url
                                 {:jsonrpc "2.0" :method "ping" :params {} :id 3}
                                 {"Mcp-Session-Id" session2-id})]
            (is (= 200 (:status r1)))
            (is (= 200 (:status r2)))
            (is (match? [{"jsonrpc" "2.0" "result" {} "id" 2}]
                        (parse-sse-body (:body r1))))
            (is (match? [{"jsonrpc" "2.0" "result" {} "id" 3}]
                        (parse-sse-body (:body r2))))))
        (finally
          (stop-test-server server-info))))))

(deftest http-session-cleanup-test
  (testing "DELETE removes a session from the store"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)]
      (try
        (is (some? (http/get-session (:sessions server-info) session-id)))

        (let [response (delete-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response))))

        (is (nil? (http/get-session (:sessions server-info) session-id)))
        (finally
          (stop-test-server server-info))))))

(deftest http-init-failure-no-session-leak-test
  (testing "Initialize with an unsupported protocol version does not leave a session in the store"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
        (let [response (post-request base-url
                                     {:jsonrpc "2.0"
                                      :method "initialize"
                                      :params {:protocolVersion "1999-01-01"
                                               :capabilities {}
                                               :clientInfo {:name "Test" :version "1.0.0"}}
                                      :id 1})]
          (is (= 200 (:status response)))
          (is (nil? (get-in response [:headers "mcp-session-id"]))
              "Mcp-Session-Id header must NOT be sent on init failure")
          (is (match? {"jsonrpc" "2.0"
                       "error" {"code" -32600 "message" "Invalid Request"}
                       "id" 1}
                      (json/read-json (:body response))))
          (is (empty? (http/all-sessions (:sessions server-info)))
              "No session must be left in the store after a failed initialize"))
        (finally
          (stop-test-server server-info))))))

(deftest http-delete-detaches-sse-stream-test
  (testing "DELETE /mcp detaches an attached SSE stream from the session"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)
          session-id (initialize-session)
          session (http/get-session (:sessions server-info) session-id)
          os (java.io.ByteArrayOutputStream.)]
      (try
         ;; Pretend an SSE stream is attached.
         (reset! (:os session) os)
         (is (some? @(:os session)))

        (let [response (delete-request base-url {"Mcp-Session-Id" session-id})]
          (is (= 200 (:status response))))

         (is (nil? (http/get-session (:sessions server-info) session-id))
             "Session removed from store")
         (is (nil? @(:os session))
             "Attached SSE stream is detached on DELETE")
        (finally
          (stop-test-server server-info))))))

(deftest http-protocol-version-negotiation-test
  (testing "Initialize accepts the supported protocol version"
    (let [session-template (create-test-session)
          server-info (start-test-server session-template)]
      (try
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

        (testing "Initialize with an unsupported version returns a JSON-RPC Invalid Request"
          (let [response (post-request base-url
                                       {:jsonrpc "2.0"
                                        :method "initialize"
                                        :params {:protocolVersion "1999-01-01"
                                                 :capabilities {}
                                                 :clientInfo {:name "Test Client" :version "1.0.0"}}
                                        :id 2})]
            (is (= 200 (:status response)))
            (is (match? {"jsonrpc" "2.0"
                         "error" {"code" -32600
                                  "message" "Invalid Request"
                                  "data" #"Invalid protocol version 1999-01-01.*2025-06-18.*"}
                         "id" 2}
                        (json/read-json (:body response))))))
        (finally
          (stop-test-server server-info))))))
