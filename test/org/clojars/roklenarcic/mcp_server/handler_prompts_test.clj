(ns org.clojars.roklenarcic.mcp-server.handler-prompts-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.prompts :as prompts]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]))

(def sample-prompt
  (server/prompt "weather-prompt"
                 "Get weather information for a location"
                 {:location "The location to get weather for"}
                 {:units "Temperature units (optional)"}
                 (fn [exchange arguments]
                   (c/prompt-resp 
                     (str "Weather for " (:location arguments))
                     [(c/text-content 
                        (str "It's sunny in " (:location arguments) 
                             (when (:units arguments) 
                               (str " (units: " (:units arguments) ")")))
                        1.0 :user)]))))

(deftest prompts-list-test
  (testing "List prompts when none exist"
    (let [session (atom {::mcp/handlers {:prompts {}}})
          result (prompts/prompts-list session {})]
      (is (match? {:prompts []} result))))

  (testing "List prompts with one prompt"
    (let [session (atom {::mcp/handlers {:prompts {"weather-prompt" (#'prompts/->prompt sample-prompt)}}})
          result (prompts/prompts-list session {})]
      (is (match? {:prompts [{:name "weather-prompt"
                              :description "Get weather information for a location"
                              :arguments [{:name :location
                                           :description "The location to get weather for"
                                           :required true}
                                          {:name :units
                                           :description "Temperature units (optional)"
                                           :required false}]}]}
                  result))))

  (testing "List prompts with cursor"
    (let [session (atom {::mcp/handlers {:prompts {"prompt1" sample-prompt
                                                   "prompt2" sample-prompt}}})
          result (prompts/prompts-list session {:cursor "page-2"})]
      (is (match? {:prompts vector?} result)))))

(deftest prompts-get-test
  (testing "Get existing prompt"
    (let [session (atom {::mcp/handlers {:prompts {"weather-prompt" sample-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "weather-prompt"
                                               :arguments {:location "New York"
                                                           :units "celsius"}})]
      (is (match? {:description "Weather for New York"
                   :messages [{:content {:text "It's sunny in New York (units: celsius)"
                                         :type "text"}}]}
                  result))))

  (testing "Get prompt with only required arguments"
    (let [session (atom {::mcp/handlers {:prompts {"weather-prompt" sample-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "weather-prompt"
                                               :arguments {:location "London"}})]
      (is (match? {:description "Weather for London"
                   :messages [{:content {:text "It's sunny in London"
                                         :type "text"}}]}
                  result))))

  (testing "Get non-existent prompt"
    (let [session (atom {::mcp/handlers {:prompts {}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "non-existent"
                                               :arguments {}})]
      (is (match? {:code -32602
                   :message "Invalid Params"
                   :data "Prompt non-existent not found"}
                  result)))))

(deftest prompt-validation-test
  (testing "Prompt with required and optional arguments"
    (let [complex-prompt (server/prompt "complex-prompt"
                                        "A complex prompt"
                                        {:required-param "This is required"}
                                        {:optional-param "This is optional"
                                         :another-optional "Another optional"}
                                        (fn [exchange arguments]
                                          (c/prompt-resp 
                                            "Complex response"
                                            [(c/text-content 
                                               (str "Required: " (:required-param arguments)
                                                    ", Optional: " (:optional-param arguments)))])))
          session (atom {::mcp/handlers {:prompts {"complex-prompt" complex-prompt}}
                         ::mcp/initialized? true})]
      
      ;; Should succeed with required parameter
      (is (match? {:description "Complex response"
                   :messages vector?}
                  (prompts/prompts-get session {:name "complex-prompt"
                                                :arguments {:required-param "test"}})))
      
      ;; Should succeed with both required and optional
      (is (match? {:description "Complex response"
                   :messages vector?}
                  (prompts/prompts-get session {:name "complex-prompt"
                                                :arguments {:required-param "test"
                                                            :optional-param "optional-value"}})))))

  (testing "Prompt argument list structure"
    (let [session (atom {::mcp/handlers {:prompts {"complex-prompt"
                                                   (#'prompts/->prompt
                                                     (server/prompt "complex-prompt"
                                                                    "Complex prompt"
                                                                    {:req1 "Required 1" :req2 "Required 2"}
                                                                    {:opt1 "Optional 1" :opt2 "Optional 2"}
                                                                    (fn [e a] (c/prompt-resp "test" []))))}}})
          result (prompts/prompts-list session {})]
      (is (= {:prompts [{:arguments [{:description "Required 1"
                                      :name :req1
                                      :required true}
                                     {:description "Required 2"
                                      :name :req2
                                      :required true}
                                     {:description "Optional 1"
                                      :name :opt1
                                      :required false}
                                     {:description "Optional 2"
                                      :name :opt2
                                      :required false}]
                         :description "Complex prompt"
                         :name "complex-prompt"}]}
             result)))))

(deftest prompt-complex-responses-test
  (testing "Prompt with multiple content types"
    (let [rich-prompt (server/prompt "rich-prompt"
                                     "Prompt with rich content"
                                     {}
                                     {}
                                     (fn [exchange arguments]
                                       (c/prompt-resp 
                                         "Rich content response"
                                         [(c/text-content "Text message" 1.0 :user)
                                          (c/image-content (byte-array [1 2 3]) "image/png" 2.0 :assistant)
                                          (c/text-content "Simple string")
                                          (c/audio-content (byte-array [4 5 6]) "audio/mp3")])))
          session (atom {::mcp/handlers {:prompts {"rich-prompt" rich-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "rich-prompt"
                                               :arguments {}})]
      (is (match? {:description "Rich content response"
                   :messages [{:content {:type "text"}}
                              {:content {:type "image"}}
                              {:content {:type "text"}}
                              {:content {:type "audio"}}]}
                  result))))

  (testing "Prompt with resource content"
    (let [resource-prompt (server/prompt "resource-prompt"
                                         "Prompt with resource"
                                         {}
                                         {}
                                         (fn [exchange arguments]
                                           (c/prompt-resp 
                                             "Resource response"
                                             [(c/embedded-content "JSON data" 1.0 :user)])))
          session (atom {::mcp/handlers {:prompts {"resource-prompt" resource-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "resource-prompt"
                                               :arguments {}})]
      (is (match? {:description "Resource response"
                   :messages [{:content {:type "resource"}}]}
                  result)))))

(deftest prompt-error-handling-test
  (testing "Missing name parameter"
    (let [session (atom {::mcp/handlers {:prompts {"test" sample-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:arguments {}})]
      (is (match? {:code -32602
                   :message "Invalid Params"}
                  result))))

  (testing "Prompt function returns invalid format"
    (let [invalid-prompt (server/prompt "invalid-prompt"
                                        "Returns invalid data"
                                        {}
                                        {}
                                        (fn [exchange arguments] "not-a-prompt-response"))
          session (atom {::mcp/handlers {:prompts {"invalid-prompt" invalid-prompt}}
                         ::mcp/initialized? true})
          result (prompts/prompts-get session {:name "invalid-prompt"
                                               :arguments {}})]
      ;; Should handle gracefully - the result might be a description with messages
      (is (or (contains? result :code)
              (contains? result :description))))))

(deftest prompt-message-conversion-test
  (testing "Message extraction from various response types"
    (let [text-content (c/text-content "Hello" 1.0 :user)]
      (are [arg result]
        (= result (#'prompts/get-prompt-result arg))
        (c/prompt-resp "Test" [text-content])
        {:description "Test"
         :messages [{:content {:annotations {:audience ["user"]
                                             :priority 1.0}
                               :text "Hello"
                               :type "text"}}]}
        text-content
        {:description nil
         :messages [{:content {:annotations {:audience ["user"] :priority 1.0}
                               :text "Hello"
                               :type "text"}}]}
        [text-content]
        {:description nil
         :messages [{:content {:annotations {:audience ["user"]
                                             :priority 1.0}
                               :text "Hello"
                               :type "text"}}]})))

  (testing "Error response handling"
    (is (= (c/invalid-params "Test error")
           (#'prompts/get-prompt-result (c/invalid-params "Test error"))))))

(deftest prompt-preprocessing-test
  (testing "Prompt preprocessing for API response"
    (let [raw-prompt {:name "test-prompt"
                      :description "Test description"
                      :required-args {:param1 "Required param"}
                      :optional-args {:param2 "Optional param"}
                      :handler (fn [e a] nil)}
          processed (#'prompts/->prompt raw-prompt)]
      (is (= {:arguments [{:description "Required param" :name :param1 :required true}
                          {:description "Optional param" :name :param2 :required false}]
              :description "Test description"
              :handler (:handler raw-prompt)
              :name "test-prompt"}
             processed))))

  (testing "Prompt preprocessing with camelCase conversion"
    (let [raw-prompt {:name "test"
                      :description "test"
                      :required-args {}
                      :optional-args {}
                      :some-kebab-key "value"}
          processed (#'prompts/->prompt raw-prompt)]
      ;; The camelcase-keys function should convert kebab-case to camelCase
      (is (contains? processed :someKebabKey)))))
