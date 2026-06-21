(ns org.clojars.roklenarcic.mcp-server.schema.harrel
  "Adapter for dev.harrel/json-schema.

  Add to your deps.edn:
    dev.harrel/json-schema {:mvn/version \"1.5.0\"}

  Usage:
    (require '[org.clojars.roklenarcic.mcp-server.schema.harrel :as harrel])
    (server/set-params-validator session (harrel/validator))"
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.protocol :as p])
  (:import (dev.harrel.jsonschema ValidatorFactory Validator Validator$Result)
           (java.net URI)))

(defn validator
  "Creates a SchemaValidator backed by dev.harrel/json-schema.

  No options required. The session's JSONSerialization instance is used to
  convert the schema and arguments to JSON strings."
  []
  (let [factory (ValidatorFactory.)]
    (reify p/SchemaValidator
      (-validate [_ json-impl schema data]
        (try
          (let [^Validator v              (.createValidator factory)
                ^String schema-json        (rpc/json-serialize json-impl schema)
                ^String data-json          (rpc/json-serialize json-impl data)
                ^URI schema-uri (.registerSchema v schema-json)
                ^Validator$Result result   (.validate v schema-uri data-json)
                errors                     (.getErrors result)]
            (when-not (.isValid result)
              (mapv #(.getError ^dev.harrel.jsonschema.Error %) errors)))
          (catch Exception e
            (log/warn e "harrel schema validation threw an exception")
            [(str "Schema validation error: " (ex-message e))]))))))
