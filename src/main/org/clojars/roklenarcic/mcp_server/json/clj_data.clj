(ns org.clojars.roklenarcic.mcp-server.json.clj-data
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(defn serde [options]
  (reify rpc/JSONSerialization
    (json-serialize [this o]
      (json/write-str o options))
    (json-deserialize [this s]
      (try
        (json/read-str s (merge {:key-fn keyword} options))
        (catch Exception e
          (log/debug "JSON Parse error" (ex-message e))
          e)))))