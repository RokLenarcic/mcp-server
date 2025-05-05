(ns org.clojars.roklenarcic.mcp-server.handler.completions
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common]))

(defn do-completion [rpc-session {{:keys [name type]} :ref :keys [argument]}]
  (log/debug "Do completion" type name "with argument" argument)
  (if-let [handler (get-in @rpc-session [::mcp/handlers :completions [type name]])]
    (do (log/debug "Found completion handler for ref" type name)
        (handler (common/create-req-session rpc-session) (:name argument) (:value argument)))
    (if-let [handler (get-in @rpc-session [::mcp/handlers :def-completion])]
      (do (log/debug "Invoking general completion handler for ref" type name)
          (handler (common/create-req-session rpc-session) type name (:name argument) (:value argument)))
      (do (log/debug "Completion" type name "cannot be resolved")
          (c/invalid-params (format "Completion %s/%s not found" type name))))))

(def handler (common/wrap-check-init do-completion))