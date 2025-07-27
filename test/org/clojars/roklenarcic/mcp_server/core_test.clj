(ns org.clojars.roklenarcic.mcp-server.core-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m])
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent CompletableFuture)))

(deftest resource-desc-test
  (testing "Basic resource description creation"
    (is (= {:annotations nil
            :description "A test file"
            :mime-type "text/plain"
            :name "Test File"
            :uri "file:///test.txt"}
           (c/resource-desc "file:///test.txt" "Test File" "A test file" "text/plain" nil))))

  (testing "Resource description with annotations"
    (is (= {:annotations [{:audience [:user
                                      :assistant]
                           :priority 1.0}]
            :description nil
            :mime-type "application/json"
            :name "Web Resource"
            :uri "http://example.com"}
           (c/resource-desc "http://example.com" "Web Resource" nil "application/json" [{:audience [:user :assistant] :priority 1.0}])))))

(deftest content-creation-test
  (testing "Text content creation"
    (let [content (c/text-content "Hello world" 1.5 :user)]
      (is (= "Hello world" (p/-con-text content)))
      (is (= 1.5 (p/-con-priority content)))
      (is (= :user (p/-con-audience content)))))

  (testing "Text content with no annotations"
    (let [content (c/text-content "Simple text")]
      (is (= "Simple text" (p/-con-text content)))
      (is (nil? (p/-con-priority content)))
      (is (nil? (p/-con-audience content)))))

  (testing "Image content creation"
    (let [data (byte-array [1 2 3 4])
          content (c/image-content data "image/png" 2.0 [:user :assistant])]
      (is (= data (p/-con-data content)))
      (is (= "image/png" (p/-con-mime-type content)))
      (is (= 2.0 (p/-con-priority content)))
      (is (= [:user :assistant] (p/-con-audience content)))))

  (testing "Audio content creation"
    (let [data (byte-array [5 6 7 8])
          content (c/audio-content data "audio/mp3")]
      (is (= data (p/-aud-data content)))
      (is (= "audio/mp3" (p/-aud-mime-type content)))
      (is (nil? (p/-con-priority content)))
      (is (nil? (p/-con-audience content)))))

  (testing "Embedded content creation"
    (let [resource-data "Some text"
          content (c/embedded-content resource-data 1.0 :user)]
      (is (= "Some text" (p/-res-body content)))
      (is (= "text/plain" (p/-res-mime content)))
      (is (= 1.0 (p/-con-priority content)))
      (is (= :user (p/-con-audience content))))))

(deftest resource-test
  (testing "Resource with string content"
    (let [res (c/resource "Hello, World!" "text/plain" "http://example.com/hello.txt")]
      (is (= "Hello, World!" (p/-res-body res)))
      (is (= "text/plain" (p/-res-mime res)))
      (is (= "http://example.com/hello.txt" (p/-res-uri res)))))

  (testing "Resource with byte array"
    (let [data (byte-array [1 2 3 4])
          res (c/resource data "application/octet-stream" nil)]
      (is (= data (p/-res-body res)))
      (is (= "application/octet-stream" (p/-res-mime res)))
      (is (nil? (p/-res-uri res)))))

  (testing "Resource with input stream"
    (let [data (byte-array [10 20 30])
          stream (ByteArrayInputStream. data)
          res (c/resource stream nil "http://example.com/data")]
      (is (= stream (p/-res-body res)))
      (is (= "application/octet-stream" (p/-res-mime res)))
      (is (= "http://example.com/data" (p/-res-uri res)))))

  (testing "Resource with automatic MIME type inference"
    (let [res (c/resource "Text content" nil nil)]
      (is (= "Text content" (p/-res-body res)))
      (is (= "text/plain" (p/-res-mime res)))
      (is (nil? (p/-res-uri res))))))

(deftest message-test
  (testing "Message creation"
    (let [content (c/text-content "Hello" 1.0 :user)
          msg (c/message :assistant content)]
      (is (= :assistant (p/-msg-role msg)))
      (is (= content (p/-msg-content msg)))))

  (testing "Message with different content types"
    (let [image-content (c/image-content (byte-array [1 2 3]) "image/png")
          msg (c/message :user image-content)]
      (is (= :user (p/-msg-role msg)))
      (is (= image-content (p/-msg-content msg))))))

(deftest prompt-response-test
  (testing "Simple prompt response"
    (let [content (c/text-content "Hello" 1.0 :user)
          resp (c/prompt-resp "Test description" [content])]
      (is (= "Test description" (p/-prompt-desc resp)))
      (is (= [content] (p/-prompt-msgs resp)))))

  (testing "Prompt response with multiple messages"
    (let [content1 (c/text-content "First message" 1.0 :user)
          content2 (c/text-content "Second message" 1.0 :assistant)
          resp (c/prompt-resp "Multi-message prompt" [content1 content2])]
      (is (= "Multi-message prompt" (p/-prompt-desc resp)))
      (is (= [content1 content2] (p/-prompt-msgs resp))))))

(deftest completion-response-test
  (testing "Basic completion response (1-arg)"
    (let [resp (c/completion-resp ["option1" "option2" "option3"])]
      (is (= ["option1" "option2" "option3"] (:values resp)))
      (is (= 3 (:total resp)))
      (is (false? (:hasMore resp)))))

  (testing "Completion response with many items (1-arg)"
    (let [many-items (map str (range 150))
          resp (c/completion-resp many-items)]
      (is (= 100 (count (:values resp))))
      (is (= 150 (:total resp)))
      (is (true? (:hasMore resp)))))

  (testing "Completion response with explicit total (2-arg)"
    (let [resp (c/completion-resp ["option1"] 10)]
      (is (= ["option1"] (:values resp)))
      (is (= 10 (:total resp)))
      (is (true? (:hasMore resp)))))

  (testing "Completion response with all parameters (3-arg)"
    (let [resp (c/completion-resp ["option1" "option2"] 5 false)]
      (is (= ["option1" "option2"] (:values resp)))
      (is (= 5 (:total resp)))
      (is (false? (:hasMore resp))))))

(deftest error-functions-test
  (testing "Invalid request error"
    (let [error (c/invalid-request "Missing field")]
      (is (instance? org.clojars.roklenarcic.mcp_server.core.JSONRPCError error))
      (is (= -32600 (:code error)))
      (is (= "Invalid Request" (:message error)))
      (is (= "Missing field" (:data error)))))

  (testing "Invalid request error with custom message"
    (let [error (c/invalid-request "Custom data" "Custom message")]
      (is (= -32600 (:code error)))
      (is (= "Custom message" (:message error)))
      (is (= "Custom data" (:data error)))))

  (testing "Invalid params error"
    (let [error (c/invalid-params "Wrong type")]
      (is (= -32602 (:code error)))
      (is (= "Invalid Params" (:message error)))
      (is (= "Wrong type" (:data error)))))

  (testing "Internal error"
    (let [error (c/internal-error "Database error" "Connection failed")]
      (is (= -32603 (:code error)))
      (is (= "Connection failed" (:message error)))
      (is (= "Database error" (:data error)))))

  (testing "Resource not found error"
    (let [error (c/resource-not-found "file:///missing.txt")]
      (is (= -32002 (:code error)))
      (is (= "Resource Not Found" (:message error)))
      (is (= "file:///missing.txt" (:data error))))))

(deftest tool-error-test
  (testing "Tool error with single content"
    (let [content (c/text-content "Error occurred" 1.0 :user)
          error (c/tool-error content)]
      (is (= content (p/-err-contents error)))))

  (testing "Tool error with multiple content items"
    (let [content1 (c/text-content "Error: " 1.0 :user)
          content2 (c/text-content "Something went wrong" 1.0 :user)
          error (c/tool-error [content1 content2])]
      (is (= [content1 content2] (p/-err-contents error))))))

(deftest model-preferences-test
  (testing "Basic model preferences"
    (let [prefs (c/model-preferences [{:name "gpt-4"} {:name "claude-3"}] nil nil)]
      (is (= [{:name "gpt-4"} {:name "claude-3"}] (:hints prefs)))
      (is (nil? (:intelligence-priority prefs)))
      (is (nil? (:speed-priority prefs)))))

  (testing "Model preferences with priorities"
    (let [prefs (c/model-preferences [] 0.8 0.2)]
      (is (= [] (:hints prefs)))
      (is (= 0.8 (:intelligence-priority prefs)))
      (is (= 0.2 (:speed-priority prefs))))))

(deftest sampling-request-test
  (testing "Basic sampling request"
    (let [content (c/text-content "Hello" 1.0 :user)
          prefs (c/model-preferences [{:name "gpt-4"}] nil nil)
          req (c/sampling-request [content] prefs "System prompt" 1000)]
      (is (= [content] (:messages req)))
      (is (= prefs (:model-preferences req)))
      (is (= "System prompt" (:system-prompt req)))
      (is (= 1000 (:max-tokens req)))))

  (testing "Sampling request with minimal parameters"
    (let [req (c/sampling-request [] nil nil nil)]
      (is (= [] (:messages req)))
      (is (nil? (:model-preferences req)))
      (is (nil? (:system-prompt req)))
      (is (nil? (:max-tokens req))))))

(deftest protocol-implementation-test
  (testing "String implements ResourceResponse"
    (let [text "Hello, World!"]
      (is (= "Hello, World!" (p/-res-body text)))
      (is (= "text/plain" (p/-res-mime text)))
      (is (nil? (p/-res-uri text)))))

  (testing "Byte array implements ResourceResponse"
    (let [data (byte-array [1 2 3 4])]
      (is (= data (p/-res-body data)))
      (is (= "application/octet-stream" (p/-res-mime data)))
      (is (nil? (p/-res-uri data)))))

  (testing "InputStream implements ResourceResponse"
    (let [data (byte-array [10 20 30])
          stream (ByteArrayInputStream. data)]
      (is (= stream (p/-res-body stream)))
      (is (= "application/octet-stream" (p/-res-mime stream)))
      (is (nil? (p/-res-uri stream))))))

(deftest content-protocols-test
  (testing "Text content implements protocols correctly"
    (let [content (c/text-content "Test text" 2.0 [:user :assistant])]
      (is (satisfies? p/Content content))
      (is (satisfies? p/TextContent content))
      (is (= "Test text" (p/-con-text content)))
      (is (= 2.0 (p/-con-priority content)))
      (is (= [:user :assistant] (p/-con-audience content)))))

  (testing "Image content implements protocols correctly"
    (let [data (byte-array [1 2 3])
          content (c/image-content data "image/png" 1.5 :user)]
      (is (satisfies? p/Content content))
      (is (satisfies? p/ImageContent content))
      (is (= data (p/-con-data content)))
      (is (= "image/png" (p/-con-mime-type content)))
      (is (= 1.5 (p/-con-priority content)))
      (is (= :user (p/-con-audience content)))))

  (testing "Audio content implements protocols correctly"
    (let [data (byte-array [4 5 6])
          content (c/audio-content data "audio/wav" 3.0 :assistant)]
      (is (satisfies? p/Content content))
      (is (satisfies? p/AudioContent content))
      (is (= data (p/-aud-data content)))
      (is (= "audio/wav" (p/-aud-mime-type content)))
      (is (= 3.0 (p/-con-priority content)))
      (is (= :assistant (p/-con-audience content)))))

  (testing "Embedded content implements protocols correctly"
    (let [resource-text "Embedded text"
          content (c/embedded-content resource-text 1.0 :user)]
      (is (satisfies? p/Content content))
      (is (satisfies? p/ResourceResponse content))
      (is (= "Embedded text" (p/-res-body content)))
      (is (= "text/plain" (p/-res-mime content)))
      (is (= 1.0 (p/-con-priority content)))
      (is (= :user (p/-con-audience content))))))

(deftest message-protocol-test
  (testing "Message implements protocol correctly"
    (let [content (c/text-content "Message content")
          msg (c/message :user content)]
      (is (satisfies? p/Message msg))
      (is (= :user (p/-msg-role msg)))
      (is (= content (p/-msg-content msg))))))

(deftest prompt-response-protocol-test
  (testing "Prompt response implements protocol correctly"
    (let [content (c/text-content "Response content")
          resp (c/prompt-resp "Description" [content])]
      (is (satisfies? p/PromptResponse resp))
      (is (= "Description" (p/-prompt-desc resp)))
      (is (= [content] (p/-prompt-msgs resp))))))

(deftest tool-error-protocol-test
  (testing "Tool error implements protocol correctly"
    (let [content (c/text-content "Error message")
          error (c/tool-error content)]
      (is (satisfies? p/ToolErrorResponse error))
      (is (= content (p/-err-contents error))))))

(deftest request-exchange-test
  (testing "Progress reporting functionality"
    (let [progress-calls (atom [])
          mock-exchange (reify c/RequestExchange
                          (req-meta [this] nil)
                          (client-spec [this] {:info {} :capabilities {}})
                          (get-session [this] (atom {}))
                          (log-msg [this level logger msg data] nil)
                          (list-roots [this] nil)
                          (list-roots [this progress-callback] nil)
                          (sampling [this req] nil)
                          (sampling [this req progress-callback] nil)
                          (report-progress [this msg]
                            (swap! progress-calls conj msg))
                          (req-cancelled-future [this] (CompletableFuture/completedFuture nil)))]

      (testing "Basic progress reporting"
        (c/report-progress mock-exchange {:progress 50 :total 100 :message "Processing..."})
        (is (= 1 (count @progress-calls)))
        (is (= {:progress 50 :total 100 :message "Processing..."} (first @progress-calls))))

      (testing "Multiple progress reports"
        (reset! progress-calls [])
        (c/report-progress mock-exchange {:progress 25 :total 100 :message "Starting..."})
        (c/report-progress mock-exchange {:progress 75 :total 100 :message "Almost done..."})
        (c/report-progress mock-exchange {:progress 100 :total 100 :message "Completed!"})

        (is (= 3 (count @progress-calls)))
        (is (= {:progress 25 :total 100 :message "Starting..."} (first @progress-calls)))
        (is (= {:progress 75 :total 100 :message "Almost done..."} (second @progress-calls)))
        (is (= {:progress 100 :total 100 :message "Completed!"} (last @progress-calls))))

      (testing "Progress with minimal data"
        (reset! progress-calls [])
        (c/report-progress mock-exchange {:message "Simple update"})
        (is (= 1 (count @progress-calls)))
        (is (= {:message "Simple update"} (first @progress-calls)))))))

(deftest request-cancellation-test
  (testing "Request cancellation functionality"
    (let [cancelled-future (atom (CompletableFuture/completedFuture nil))
          mock-exchange (reify c/RequestExchange
                          (req-meta [this] nil)
                          (client-spec [this] {:info {} :capabilities {}})
                          (get-session [this] (atom {}))
                          (log-msg [this level logger msg data] nil)
                          (list-roots [this] nil)
                          (list-roots [this progress-callback] nil)
                          (sampling [this req] nil)
                          (sampling [this req progress-callback] nil)
                          (report-progress [this msg] false)
                          (req-cancelled-future [this] @cancelled-future))]

      (testing "Initially not cancelled"
        (let [future (c/req-cancelled-future mock-exchange)]
          (is (.isDone future))
          (is (nil? (.get future)))))

      (testing "Can be cancelled with reason"
        (let [cancelled-with-reason (CompletableFuture/completedFuture "User requested cancellation")]
          (reset! cancelled-future cancelled-with-reason)
          (let [future (c/req-cancelled-future mock-exchange)]
            (is (.isDone future))
            (is (= "User requested cancellation" (.get future)))))))))

(deftest edge-cases-test
  (testing "Empty collections and nil values"
    (let [empty-completion (c/completion-resp [])
          empty-prefs (c/model-preferences [] nil nil)
          empty-sampling (c/sampling-request [] nil nil nil)]
      (is (= [] (:values empty-completion)))
      (is (= 0 (:total empty-completion)))
      (is (false? (:hasMore empty-completion)))

      (is (= [] (:hints empty-prefs)))
      (is (nil? (:intelligence-priority empty-prefs)))
      (is (nil? (:speed-priority empty-prefs)))

      (is (= [] (:messages empty-sampling)))
      (is (nil? (:model-preferences empty-sampling)))))

  (testing "Content with nil annotations"
    (let [text-content (c/text-content "Text" nil nil)
          image-content (c/image-content (byte-array [1]) "image/png" nil nil)]
      (is (nil? (p/-con-priority text-content)))
      (is (nil? (p/-con-audience text-content)))
      (is (nil? (p/-con-priority image-content)))
      (is (nil? (p/-con-audience image-content)))))

  (testing "Resource with all nil parameters"
    (let [res (c/resource "content" nil nil)]
      (is (= "content" (p/-res-body res)))
      (is (= "text/plain" (p/-res-mime res)))
      (is (nil? (p/-res-uri res))))))
