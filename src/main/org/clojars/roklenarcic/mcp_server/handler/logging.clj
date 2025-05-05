(ns org.clojars.roklenarcic.mcp-server.handler.logging
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(def clj-logging-level
  {:debug :debug
   :info :info
   :notice :info
   :warning :warn
   :error :error
   :critical :error
   :alert :fatal
   :emergency :fatal})

(defn logging-set-level [rpc-session {:keys [level]}]
  (if (not-any? #(= (name %) level) (keys clj-logging-level))
    (c/invalid-params (str "Unsupported logging level " level))
    (do (swap! rpc-session assoc ::mcp/logging-level level)
        {})))

(defn do-log [rpc-session level logger msg data]
  (log/logp (clj-logging-level level) logger msg (pr-str data))
  (when-let [level (::mcp/logging-level @rpc-session)]
    (rpc/send-notification
      rpc-session
      "notifications/message"
      {:level (name level)
       :logger logger
       :data {:error msg
              :details data}})))