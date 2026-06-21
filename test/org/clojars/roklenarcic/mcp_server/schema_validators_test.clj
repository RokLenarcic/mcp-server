(ns org.clojars.roklenarcic.mcp-server.schema-validators-test
  "Integration tests for the three built-in SchemaValidator adapters.
  Each adapter is exercised against a real JSON Schema and a real JSON serializer."
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.schema.harrel :as harrel]
            [org.clojars.roklenarcic.mcp-server.schema.json-skema :as skema]
            [org.clojars.roklenarcic.mcp-server.schema.networknt :as nnt]))

(def ^:private json-impl (charred/serde {}))

(def ^:private string-schema {:type "string"})
(def ^:private number-schema {:type "number"})
(def ^:private obj-schema
  {:type "object"
   :properties {:name {:type "string"}
                :age  {:type "number"}}
   :required ["name"]})

(defn- validate [v schema data]
  (p/-validate v json-impl schema data))

(defn- errors-are-strings? [errs]
  (and (seq errs) (every? string? errs)))

;;; ──────────────────────────────────────────────────────────────
;;; com.networknt/json-schema-validator
;;; ──────────────────────────────────────────────────────────────

(deftest networknt-valid-data-test
  (let [v (nnt/validator)]
    (are [schema data] (nil? (validate v schema data))
      string-schema "hello"
      number-schema 42
      obj-schema    {:name "Alice" :age 30}
      obj-schema    {:name "Bob"})))          ; age is optional

(deftest networknt-invalid-data-test
  (let [v (nnt/validator)]
    (are [schema data] (errors-are-strings? (validate v schema data))
      string-schema 42
      number-schema "not-a-number"
      obj-schema    {}                        ; missing required "name"
      obj-schema    {:name 123})))            ; wrong type for name

(deftest networknt-draft-option-test
  (testing "explicit draft selection still validates correctly"
    (let [v (nnt/validator {:draft :draft-2020-12})]
      (is (nil?  (validate v string-schema "ok")))
      (is (seq   (validate v string-schema 0)))))
  (testing "unknown draft throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (nnt/validator {:draft :draft-99})))))

;;; ──────────────────────────────────────────────────────────────
;;; com.github.erosb/json-sKema
;;; ──────────────────────────────────────────────────────────────

(deftest skema-valid-data-test
  (let [v (skema/validator)]
    (are [schema data] (nil? (validate v schema data))
      string-schema "hello"
      number-schema 42
      obj-schema    {:name "Alice" :age 30}
      obj-schema    {:name "Bob"})))

(deftest skema-invalid-data-test
  (let [v (skema/validator)]
    (are [schema data] (errors-are-strings? (validate v schema data))
      string-schema 42
      number-schema "not-a-number"
      obj-schema    {}
      obj-schema    {:name 123})))

;;; ──────────────────────────────────────────────────────────────
;;; dev.harrel/json-schema
;;; ──────────────────────────────────────────────────────────────

(deftest harrel-valid-data-test
  (let [v (harrel/validator)]
    (are [schema data] (nil? (validate v schema data))
      string-schema "hello"
      number-schema 42
      obj-schema    {:name "Alice" :age 30}
      obj-schema    {:name "Bob"})))

(deftest harrel-invalid-data-test
  (let [v (harrel/validator)]
    (are [schema data] (errors-are-strings? (validate v schema data))
      string-schema 42
      number-schema "not-a-number"
      obj-schema    {}
      obj-schema    {:name 123})))
