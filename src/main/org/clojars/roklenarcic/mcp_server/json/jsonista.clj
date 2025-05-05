(ns org.clojars.roklenarcic.mcp-server.json.jsonista
  (:require [jsonista.core :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(defn serde [object-mapper-opts]
  (let [om (json/object-mapper (merge {:encode-key-fn true, :decode-key-fn true}
                                      object-mapper-opts))]
    (reify rpc/JSONSerialization
      (json-serialize [this o]
        (json/write-value-as-string o om))
      (json-deserialize [this s]
        (try
          (json/read-value s om)
          (catch Exception e
            (log/debug "JSON Parse error" (ex-message e))
            e))))))