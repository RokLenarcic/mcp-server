(ns org.clojars.roklenarcic.mcp-server.handler.tools
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply]]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn map->tool-message [resp]
  (if (instance? JSONRPCError resp)
    resp
    {:content (common/->content-vector resp)
     :isError (satisfies? p/ToolErrorResponse resp)}))

(defn tools-list [rpc-session _]
  (log/debug "Tool list requested")
  (let [tools (or (-> @rpc-session ::mcp/handlers :tools vals) [])]
    (log/debug "Tool list returned" tools)
    {:tools tools}))

(defn tools-call [rpc-session {:keys [name arguments]}]
  (log/debug "Invoking tool" name "with arguments" arguments)
  (if-let [tool-handler (get-in @rpc-session [::mcp/handlers :tools name :handler])]
    (-> (tool-handler (common/create-req-session rpc-session) arguments)
        (papply map->tool-message))
    (do (log/debug "Tool" name "not found")
        (c/invalid-params (format "Tool %s not found" name)))))

(defn add-tool-handlers [m]
  (assoc m "tools/list" (wrap-check-init tools-list) "tools/call" (wrap-check-init tools-call)))