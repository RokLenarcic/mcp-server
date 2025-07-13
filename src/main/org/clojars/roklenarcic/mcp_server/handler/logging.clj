(ns org.clojars.roklenarcic.mcp-server.handler.logging
  "This namespace handles logging-related MCP protocol operations.
   It manages log level configuration, message forwarding to clients,
   and integration with Clojure's logging system."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]))

(def clj-logging-level
  "Maps MCP protocol log levels to Clojure tools.logging levels.
   
   MCP supports more granular logging levels than most Clojure logging
   frameworks, so some levels are mapped to the closest equivalent."
  {:debug :debug      ; Detailed debug information
   :info :info        ; General informational messages
   :notice :info      ; Normal but significant conditions (mapped to info)
   :warning :warn     ; Warning conditions
   :error :error      ; Error conditions
   :critical :error   ; Critical conditions (mapped to error)
   :alert :fatal      ; Action must be taken immediately
   :emergency :fatal  ; System is unusable
   })

(defn logging-set-level
  "Handles logging/setLevel requests from the client.
   
   This function allows clients to configure the minimum logging level
   for messages that should be forwarded to them. Only messages at or
   above this level will be sent to the client via notifications.
   
   Parameters:
   - rpc-session: the session atom
   - params: request parameters containing :level (string log level name)
   
   Returns an empty map on success, or an error for invalid levels."
  [rpc-session {:keys [level]}]
  (log/debug "Client requesting logging level change to:" level)
  (if (not-any? #(= (name %) level) (keys clj-logging-level))
    (do
      (log/info "Client requested unsupported logging level:" level)
      (log/trace "Supported levels:" (keys clj-logging-level))
      (c/invalid-params (str "Unsupported logging level " level)))
    (do
      (log/trace "Setting client logging level to:" level)
      (swap! rpc-session assoc ::mcp/logging-level (keyword level))
      {})))

(defn do-log 
  "Logs a message both locally and optionally forwards it to the client.
   
   This function handles the dual responsibility of:
   1. Logging the message using Clojure's logging system
   2. Optionally forwarding the message to the client if logging is enabled
   
   Parameters:
   - rpc-session: the session atom
   - level: log level keyword (:debug, :info, :warning, :error, etc.)
   - logger: string identifying the logger/component
   - msg: log message string
   - data: additional structured data to include
   
   The message is always logged locally. It's only sent to the client if:
   - The session has a logging level configured
   - The message level meets or exceeds the configured threshold"
  [rpc-session level logger msg data]
  (log/logp (clj-logging-level level) logger msg (pr-str data))
  (when-let [level (::mcp/logging-level @rpc-session)]
    (log/debug "Forwarding log message to client - level:" level "logger:" logger)
    (rpc/send-notification
      rpc-session
      "notifications/message"
      {:level (name level)
       :logger logger
       :data {:error msg
              :details data}})))