(ns org.clojars.roklenarcic.mcp-server.handler.tools
  "This namespace handles tool-related MCP protocol operations.
  Tools are functions that clients can call with structured parameters.
  This handler manages tool registration, listing, and execution."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply]]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn map->tool-message
  "Converts a tool execution result to MCP tool response format.
   
   Parameters:
   - resp: tool execution result (can be JSONRPCError, ToolErrorResponse, or content)
   
   Returns a map in MCP tool response format with :content and :isError keys."
  [resp]
  (if (instance? JSONRPCError resp)
    (do (log/debug "Tool returned JSONRPCError - code:" (:code resp))
        resp)
    (if (satisfies? p/ToolErrorResponse resp)
      (do (log/debug "Tool returned error response")
          {:content (common/->content-vector resp)
           :isError true})
      (do (log/debug "Tool returned normal response")
          {:content (common/->content-vector resp)
           :isError false}))))

(defn tools-list 
  "Handles tools/list requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - _: unused parameters
   
   Returns a map with :tools key containing the list of available tools."
  [rpc-session _]
  (log/debug "Client requested tool list")
  (let [tools (or (-> @rpc-session ::mcp/handlers :tools vals) [])]
    (log/trace "Returning" (count tools) "tools:" (mapv :name tools))
    {:tools tools}))

(defn tools-call 
  "Handles tools/call requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - params: request parameters containing :name (tool name) and :arguments (tool arguments)
   
   Returns the result of tool execution, or an error if the tool is not found."
  [rpc-session {:keys [name arguments] :as params}]
  (log/debug "Client requested tool execution - name:" name)
  (log/trace "Tool arguments:" arguments)
  
  (if-let [tool-handler (get-in @rpc-session [::mcp/handlers :tools name :handler])]
    (do (log/trace "Found tool handler, executing tool:" name)
        (-> (tool-handler (common/create-req-session' rpc-session params) arguments)
            (papply map->tool-message)))
    (do
      (log/warn "Tool not found:" name)
      (log/trace "Available tools:" (keys (get-in @rpc-session [::mcp/handlers :tools])))
      (c/invalid-params (format "Tool %s not found" name)))))

(defn add-tool-handlers [m]
  (assoc m "tools/list" (wrap-check-init tools-list) "tools/call" (wrap-check-init tools-call)))