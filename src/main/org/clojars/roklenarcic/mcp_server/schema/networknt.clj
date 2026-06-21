(ns org.clojars.roklenarcic.mcp-server.schema.networknt
  "Adapter for com.networknt/json-schema-validator.

  Add to your deps.edn:
    com.networknt/json-schema-validator {:mvn/version \"1.5.1\"}

  Usage:
    (require '[org.clojars.roklenarcic.mcp-server.schema.networknt :as nnt])
    (server/set-params-validator session (nnt/validator))

  With a non-default schema draft:
    (server/set-params-validator session (nnt/validator {:draft :draft-2020-12}))"
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.protocol :as p])
  (:import (com.networknt.schema JsonSchema JsonSchemaFactory SpecVersion$VersionFlag ValidationMessage)
           (com.fasterxml.jackson.databind JsonNode ObjectMapper)))

(def ^:private draft-map
  {:draft-04 SpecVersion$VersionFlag/V4
   :draft-06 SpecVersion$VersionFlag/V6
   :draft-07 SpecVersion$VersionFlag/V7
   :draft-2019-09 SpecVersion$VersionFlag/V201909
   :draft-2020-12 SpecVersion$VersionFlag/V202012})

(def ^:private ^ObjectMapper default-om (ObjectMapper.))

(defn validator
  "Creates a SchemaValidator backed by com.networknt/json-schema-validator.

  Options:
  - :draft  JSON Schema draft to use. Default: :draft-07.
            Supported: :draft-04 :draft-06 :draft-07 :draft-2019-09 :draft-2020-12

  The session's JSONSerialization instance is used to convert the schema and
  arguments to JSON strings. Jackson's ObjectMapper then parses those strings
  into JsonNodes for validation."
  ([] (validator {}))
  ([{:keys [draft] :or {draft :draft-07}}]
   (let [version-flag (or (get draft-map draft)
                          (throw (ex-info "Unknown JSON Schema draft"
                                          {:draft draft
                                           :supported (keys draft-map)})))
         factory (JsonSchemaFactory/getInstance version-flag)]
     (reify p/SchemaValidator
       (-validate [_ json-impl schema data]
          (try
            (let [^JsonNode schema-node (.readTree default-om ^String (rpc/json-serialize json-impl schema))
                  ^JsonNode data-node   (.readTree default-om ^String (rpc/json-serialize json-impl data))
                  ^JsonSchema json-schema (.getSchema factory schema-node)
                  errors      (.validate json-schema data-node)]
              (when (seq errors)
                (mapv #(.getMessage ^ValidationMessage %) errors)))
           (catch Exception e
             (log/warn e "networknt schema validation threw an exception")
             [(str "Schema validation error: " (ex-message e))])))))))