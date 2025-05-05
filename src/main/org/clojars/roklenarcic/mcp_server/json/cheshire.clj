(ns org.clojars.roklenarcic.mcp-server.json.cheshire
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(defn serde [options]
  (reify rpc/JSONSerialization
    (json-serialize [this o]
      (json/generate-string o options))
    (json-deserialize [this s]
      (try
        (json/parse-string s keyword)
        (catch Exception e
          (log/debug "JSON Parse error" (ex-message e))
          e)))))