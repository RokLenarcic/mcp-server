(ns org.clojars.roklenarcic.mcp-server.schema.json-skema
  "Adapter for com.github.erosb/json-sKema.

  Add to your deps.edn:
    com.github.erosb/json-sKema {:mvn/version \"0.18.0\"}

  Usage:
    (require '[org.clojars.roklenarcic.mcp-server.schema.json-skema :as skema])
    (server/set-params-validator session (skema/validator))"
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.protocol :as p])
  (:import (com.github.erosb.jsonsKema JsonParser SchemaLoader ValidationFailure Validator)))

(defn- collect-errors
  "Recursively collects all leaf-level error messages from a ValidationFailure."
  [^ValidationFailure failure]
  (let [causes (.getCauses failure)]
    (if (empty? causes)
      [(.getMessage failure)]
      (into [] (mapcat collect-errors causes)))))

(defn validator
  "Creates a SchemaValidator backed by com.github.erosb/json-sKema.

  No options required. The session's JSONSerialization instance is used to
  convert the schema and arguments to JSON strings."
  []
  (reify p/SchemaValidator
    (-validate [_ json-impl schema data]
      (try
        (let [schema-json  (rpc/json-serialize json-impl schema)
              data-json    (rpc/json-serialize json-impl data)
              schema-val (.parse (JsonParser. schema-json))
              compiled (.load (SchemaLoader. schema-val))
              instance-val (.parse (JsonParser. data-json))
              failure (.validate (Validator/forSchema compiled) instance-val)]
          (when failure
            (collect-errors failure)))
        (catch Exception e
          (log/warn e "json-sKema schema validation threw an exception")
          [(str "Schema validation error: " (ex-message e))])))))
