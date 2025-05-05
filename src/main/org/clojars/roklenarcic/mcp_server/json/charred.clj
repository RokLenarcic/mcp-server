(ns org.clojars.roklenarcic.mcp-server.json.charred
  (:require [charred.api :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc])
  (:import (charred CharredException)
           (java.io EOFException)))

(defn serde [charred-options]
  (reify rpc/JSONSerialization
    (json-serialize [this o]
      (json/write-json-str o charred-options))
    (json-deserialize [this s]
      (try
        (json/read-json s (merge {:key-fn keyword} charred-options))
        (catch EOFException e e)
        (catch CharredException e
          (log/debug "JSON Parse error" (ex-message e))
          e)))))