(ns org.clojars.roklenarcic.mcp-server.json.babashka
  (:require [babashka.json :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(defn serde [opts]
  (reify rpc/JSONSerialization
    (json-serialize [this o]
      (json/write-str o opts))
    (json-deserialize [this s]
      (try
        (json/read-str s (merge {:key-fn keyword} opts))
        (catch Exception e
          (log/debug "JSON Parse error" (ex-message e))
          e)))))