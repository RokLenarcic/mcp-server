(ns org.clojars.roklenarcic.mcp-server.resource-lookup-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.resource.lookup :as lookup]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.resources :as res]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [org.clojars.roklenarcic.mcp-server.server :as server])
  (:import (org.clojars.roklenarcic.mcp_server.resource.lookup LookupMapResources)))

(deftest lookup-map-creation-test
  (testing "Create lookup map with subscriptions enabled"
    (let [lookup-map (lookup/lookup-map true)]
      (is (instance? LookupMapResources lookup-map))
      (is (true? (res/supports-subscriptions? lookup-map)))
      (is (true? (res/supports-list-changed? lookup-map)))))

  (testing "Create lookup map with subscriptions disabled"
    (let [lookup-map (lookup/lookup-map false)]
      (is (instance? LookupMapResources lookup-map))
      (is (false? (res/supports-subscriptions? lookup-map)))
      (is (true? (res/supports-list-changed? lookup-map))))))

(deftest add-resource-test
  (testing "Add resource to session"
    (let [session (atom {::mcp/resource-list {}})
          resource {:uri "file:///test.txt" :name "Test File" :description "A test file" :mime-type "text/plain"}
          handler-fn (fn [exchange uri] "file content")]
      
      (lookup/add-resource session resource handler-fn)

      (let [resources-map (::mcp/resource-list @session)]
        (is (contains? resources-map "file:///test.txt"))
        (is (= resource (dissoc (get resources-map "file:///test.txt") :handler)))
        (is (fn? (:handler (get resources-map "file:///test.txt")))))))

  (testing "Add multiple resources"
    (let [session (atom {::mcp/resource-list {}})
          resource1 {:uri "file:///test1.txt" :name "Test File 1" :description "First test" :mime-type "text/plain"}
          resource2 {:uri "file:///test2.txt" :name "Test File 2" :description "Second test" :mime-type "text/plain"}
          handler1 (fn [exchange uri] "content 1")
          handler2 (fn [exchange uri] "content 2")]
      
      (lookup/add-resource session resource1 handler1)
      (lookup/add-resource session resource2 handler2)
      
      (let [resources-map (::mcp/resource-list @session)]
        (is (= 2 (count resources-map)))
        (is (contains? resources-map "file:///test1.txt"))
        (is (contains? resources-map "file:///test2.txt")))))

  (testing "Overwrite existing resource"
    (let [session (atom {::mcp/resource-list {}})
          resource1 {:uri "file:///test.txt" :name "Test File" :description "Original" :mime-type "text/plain"}
          resource2 {:uri "file:///test.txt" :name "Test File Updated" :description "Updated" :mime-type "text/plain"}
          handler1 (fn [exchange uri] "original content")
          handler2 (fn [exchange uri] "updated content")]
      
      (lookup/add-resource session resource1 handler1)
      (lookup/add-resource session resource2 handler2)
      
      (let [resources-map (::mcp/resource-list @session)
            resource-entry (get resources-map "file:///test.txt")]
        (is (= 1 (count resources-map)))
        (is (= "Test File Updated" (:name resource-entry)))
        (is (= "updated content" ((:handler resource-entry) nil "file:///test.txt"))))))

  (testing "Add resource without URI throws exception"
    (let [session (atom {::mcp/resource-list {}})
          resource {:name "No URI" :description "Missing URI"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Resource must have a :uri"
                            (lookup/add-resource session resource (fn [e u] "content")))))))

(deftest remove-resource-test
  (testing "Remove existing resource"
    (let [session (atom {::mcp/resource-list {}})
          resource {:uri "file:///test.txt" :name "Test File" :description "A test file" :mime-type "text/plain"}
          handler-fn (fn [exchange uri] "file content")]
      
      (lookup/add-resource session resource handler-fn)
      (is (= 1 (count (::mcp/resource-list @session))))
      
      (lookup/remove-resource session "file:///test.txt")
      (is (= 0 (count (::mcp/resource-list @session))))))

  (testing "Remove non-existent resource"
    (let [session (atom {::mcp/resource-list {}})]
      ;; Should not throw an error
      (lookup/remove-resource session "file:///nonexistent.txt")
      (is (= 0 (count (::mcp/resource-list @session)))))))

(deftest resource-protocol-implementation-test
  (testing "List resources when none exist"
    (let [lookup-impl (lookup/lookup-map false)
          exchange (server/exchange (atom {}))
          result (res/list-resources lookup-impl exchange nil)]
      (is (match? {:resources []
                   :next-cursor nil}
                  result))))

  (testing "List resources with some resources"
    (let [lookup-impl (lookup/lookup-map false)
          resource1 {:uri "file:///test1.txt" :name "Test File 1" :description "First" :mime-type "text/plain" :handler (fn [e u] "content1")}
          resource2 {:uri "file:///test2.txt" :name "Test File 2" :description "Second" :mime-type "text/plain" :handler (fn [e u] "content2")}
          session (atom {::mcp/resource-list {"file:///test1.txt" resource1
                                              "file:///test2.txt" resource2}})
          exchange (server/exchange session)
          result (res/list-resources lookup-impl exchange nil)]
      (is (match? {:resources [{:uri "file:///test1.txt"
                                :name "Test File 1"
                                :description "First"
                                :mimeType "text/plain"}
                               {:uri "file:///test2.txt"
                                :name "Test File 2"
                                :description "Second"
                                :mimeType "text/plain"}]
                   :next-cursor nil}
                  result))))

  (testing "Get existing resource"
    (let [lookup-impl (lookup/lookup-map false)
          resource {:uri "file:///test.txt" :name "Test" :handler (fn [e u] "content")}
          exchange (server/exchange (atom {::mcp/resource-list {"file:///test.txt" resource}}))
          result (res/get-resource lookup-impl exchange "file:///test.txt")]
      (is (= resource result))))

  (testing "Get non-existent resource"
    (let [lookup-impl (lookup/lookup-map false)
          exchange (server/exchange (atom {}))
          result (res/get-resource lookup-impl exchange "file:///nonexistent.txt")]
      (is (nil? result)))))

(deftest subscription-test
  (testing "Subscribe to resource"
    (let [lookup-impl (lookup/lookup-map true)
          session (atom {::mcp/resource-subscriptions #{}})
          exchange (server/exchange session)]
      
      (res/subscribe lookup-impl exchange "file:///test.txt")
      (is (contains? (::mcp/resource-subscriptions @session) "file:///test.txt"))
      (is (true? (res/subscribed? lookup-impl exchange "file:///test.txt")))
      (res/unsubscribe lookup-impl exchange "file:///test.txt")
      (is (false? (res/subscribed? lookup-impl exchange "file:///test.txt")))
      (is (not (contains? (::mcp/resource-subscriptions @session) "file:///test.txt")))))

  (testing "Subscription management with multiple resources"
    (let [lookup-impl (lookup/lookup-map true)
          session (atom {::mcp/resource-subscriptions #{}})
          exchange (server/exchange session)]
      
      ;; Subscribe to multiple resources
      (res/subscribe lookup-impl exchange "file:///test1.txt")
      (res/subscribe lookup-impl exchange "file:///test2.txt")
      (is (= #{"file:///test1.txt" "file:///test2.txt"} 
             (::mcp/resource-subscriptions @session)))
      
      ;; Unsubscribe from one
      (res/unsubscribe lookup-impl exchange "file:///test1.txt")
      (is (= #{"file:///test2.txt"} 
             (::mcp/resource-subscriptions @session)))
      
      ;; Check status
      (is (false? (res/subscribed? lookup-impl exchange "file:///test1.txt")))
      (is (true? (res/subscribed? lookup-impl exchange "file:///test2.txt"))))))

(deftest resource-integration-test
  (testing "End-to-end resource workflow"
    (let [lookup-impl (lookup/lookup-map true)
          session (atom {})
          exchange (server/exchange session)
          
          ;; Add several different types of resources
          text-resource {:uri "file:///text.txt" :name "Text File" :description "Plain text" :mime-type "text/plain"}
          json-resource {:uri "file:///data.json" :name "JSON Data" :description "JSON content" :mime-type "application/json"}
          binary-resource {:uri "file:///image.png" :name "Image" :description "Binary image" :mime-type "image/png"}]
      
      ;; Add resources with different handlers
      (lookup/add-resource session text-resource 
                           (fn [exchange uri] "This is plain text content"))
      
      (lookup/add-resource session json-resource 
                           (fn [exchange uri] "{\"key\": \"value\", \"number\": 42}"))
      
      (lookup/add-resource session binary-resource 
                           (fn [exchange uri] (byte-array [-119 80 78 71]))) ; PNG header
      
      ;; List all resources
      (let [list-result (res/list-resources lookup-impl exchange nil)]
        (is (= 3 (count (:resources list-result))))
        (is (some #(= "file:///text.txt" (:uri %)) (:resources list-result)))
        (is (some #(= "file:///data.json" (:uri %)) (:resources list-result)))
        (is (some #(= "file:///image.png" (:uri %)) (:resources list-result))))
      
      ;; Get each resource
      (let [text-resource (res/get-resource lookup-impl exchange "file:///text.txt")
            json-resource (res/get-resource lookup-impl exchange "file:///data.json")
            binary-resource (res/get-resource lookup-impl exchange "file:///image.png")]
        
        (is (= "This is plain text content" ((:handler text-resource) exchange "file:///text.txt")))
        (is (= "{\"key\": \"value\", \"number\": 42}" ((:handler json-resource) exchange "file:///data.json")))
        (is (= [-119 80 78 71] (vec ((:handler binary-resource) exchange "file:///image.png")))))
      
      ;; Test subscriptions
      (res/subscribe lookup-impl exchange "file:///text.txt")
      (res/subscribe lookup-impl exchange "file:///data.json")
      (is (= #{"file:///text.txt" "file:///data.json"} 
             (::mcp/resource-subscriptions @session)))
      
      ;; Remove a resource
      (lookup/remove-resource session "file:///text.txt")
      
      ;; Verify it's gone
      (let [list-result-after (res/list-resources lookup-impl exchange nil)]
        (is (= 2 (count (:resources list-result-after))))
        (is (not (some #(= "file:///text.txt" (:uri %)) (:resources list-result-after)))))
      
      ;; Try to get removed resource
      (let [missing-resource (res/get-resource lookup-impl exchange "file:///text.txt")]
        (is (nil? missing-resource)))))

  (testing "Resource handler execution"
    (let [session (atom {::mcp/resource-list {}})
          counter (atom 0)
          resource {:uri "file:///counter.txt" :name "Counter" :description "Counting resource" :mime-type "text/plain"}
          handler-fn (fn [exchange uri] 
                       (swap! counter inc)
                       (str "Count: " @counter))]
      
      (lookup/add-resource session resource handler-fn)
      
      (let [resource-obj (get-in @session [::mcp/resource-list "file:///counter.txt"])]
        ;; Call handler multiple times
        (is (= "Count: 1" ((:handler resource-obj) nil "file:///counter.txt")))
        (is (= "Count: 2" ((:handler resource-obj) nil "file:///counter.txt")))
        (is (= "Count: 3" ((:handler resource-obj) nil "file:///counter.txt")))
        (is (= 3 @counter))))))

(deftest concurrent-resource-access-test
  (testing "Concurrent resource operations"
    (let [session (atom {::mcp/resource-list {}})
          resource {:uri "file:///concurrent.txt" :name "Concurrent" :description "Concurrent access" :mime-type "text/plain"}
          counter (atom 0)
          handler-fn (fn [exchange uri] 
                       (swap! counter inc)
                       (Thread/sleep 10) ; Simulate some work
                       (str "Access #" @counter))]
      
      (lookup/add-resource session resource handler-fn)
      
      ;; Simulate concurrent access to the resource
      (let [futures (doall (for [i (range 5)]
                             (future 
                               (let [resource-obj (get-in @session [::mcp/resource-list "file:///concurrent.txt"])]
                                 ((:handler resource-obj) nil "file:///concurrent.txt")))))
            results (map deref futures)]
        
        ;; All should succeed
        (is (= 5 (count results)))
        (is (every? string? results))
        (is (every? #(.startsWith % "Access #") results))
        
        ;; Counter should have been incremented 5 times
        (is (= 5 @counter)))))

  (testing "Concurrent add/remove operations"
    (let [session (atom {::mcp/resource-list {}})
          futures (doall (for [i (range 10)]
                           (future
                             (let [resource {:uri (str "file:///test" i ".txt")
                                             :name (str "Test " i)
                                             :description (str "Test file " i)
                                             :mime-type "text/plain"}]
                               (lookup/add-resource session resource (fn [e u] (str "content " i)))
                               (when (even? i)
                                 (lookup/remove-resource session (str "file:///test" i ".txt")))))))]
      
      ;; Wait for all operations to complete
      (doseq [f futures] (deref f))
      
      ;; Should have odd-numbered resources remaining
      (let [remaining-resources (::mcp/resource-list @session)]
        (is (= 5 (count remaining-resources)))))))

(deftest error-handling-test
  (testing "Resource handler that throws exception"
    (let [session (atom {::mcp/resource-list {}})
          resource {:uri "file:///error.txt" :name "Error" :description "Throws error" :mime-type "text/plain"}
          handler-fn (fn [exchange uri] (throw (RuntimeException. "Resource handler error")))]
      
      (lookup/add-resource session resource handler-fn)
      
      (let [resource-obj (get-in @session [::mcp/resource-list "file:///error.txt"])]
        (is (thrown-with-msg? RuntimeException #"Resource handler error"
                              ((:handler resource-obj) nil "file:///error.txt"))))))

  (testing "Invalid resource data"
    (let [session (atom {::mcp/resource-list {}})]
      ;; Missing URI should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Resource must have a :uri"
                            (lookup/add-resource session {:name "No URI"} (fn [e u] "content"))))
      
      ;; Session should remain unchanged
      (is (= 0 (count (::mcp/resource-list @session)))))))

(deftest camelcase-conversion-test
  (testing "Resource list converts keys to camelCase"
    (let [lookup-impl (lookup/lookup-map false)
          resource {:uri "file:///test.txt" 
                    :name "Test File" 
                    :description "Test description"
                    :mime-type "text/plain"
                    :handler (fn [e u] "content")}
          exchange-atom (atom {::mcp/resource-list {"file:///test.txt" resource}})
          exchange (reify c/RequestExchange
                     (get-session [this] exchange-atom))
          result (res/list-resources lookup-impl exchange nil)]
      
      ;; Should convert mime-type to mimeType
      (let [resource-desc (first (:resources result))]
        (is (contains? resource-desc :mimeType))
        (is (not (contains? resource-desc :mime-type)))
        (is (= "text/plain" (:mimeType resource-desc)))
        ;; Handler should be stripped from the response
        (is (not (contains? resource-desc :handler)))))))
