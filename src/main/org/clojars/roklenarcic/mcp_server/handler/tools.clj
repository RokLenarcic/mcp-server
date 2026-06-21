(ns org.clojars.roklenarcic.mcp-server.handler.tools
  "This namespace handles tool-related MCP protocol operations.
  Tools are functions that clients can call with structured parameters.
  This handler manages tool registration, listing, and execution."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply ?assoc]]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]]
            [org.clojars.roklenarcic.mcp-server.handler.pagination :as pagination])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn map->tool-message
  "Converts a tool execution result to MCP tool response format.

   Parameters:
   - resp: tool execution result. May be a JSONRPCError, a ToolErrorResponse,
     a ToolResult (structured + displayable content, MCP 2025-06-18), or any
     content value (single content object, collection of content, string,
     etc.).

   Returns a map in MCP tool response format with :content, optional
   :structuredContent, optional :_meta, and :isError keys."
  [resp]
  (cond
    (instance? JSONRPCError resp)
    (do (log/debug "Tool returned JSONRPCError - code:" (:code resp))
        resp)

    (satisfies? p/ToolErrorResponse resp)
    (do (log/debug "Tool returned error response")
        {:content (common/->content-vector resp)
         :isError true})

    (satisfies? p/ToolResult resp)
    (do (log/debug "Tool returned structured result")
        (-> {:content (common/->content-vector (p/-result-content resp))
             :structuredContent (p/-result-structured resp)
             :isError false}
            (?assoc :_meta (p/-result-meta resp))))

    :else
    (do (log/debug "Tool returned normal response")
        {:content (common/->content-vector resp)
         :isError false})))

(defn tools-list
  "Handles tools/list requests from the client.

   Parameters:
   - rpc-session: the session atom
   - cursor: optional pagination cursor from params

   Returns a map with :tools key containing the (possibly paginated) list of
   available tools. :nextCursor is included only when more pages remain."
  [rpc-session req-meta {:keys [cursor]}]
  (log/debug "Client requested tool list")
  (let [page-size (::mcp/page-size @rpc-session)
        tools (sort-by :name (or (-> @rpc-session ::mcp/handlers :tools vals) []))
        {:keys [items nextCursor]} (pagination/paginate
                                    (mapv #(dissoc % :handler) tools)
                                    :name cursor page-size)]
    (log/trace "Returning" (count items) "tools")
    (?assoc {:tools items} :nextCursor nextCursor)))

(defn tools-call
  "Handles tools/call requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - params: request parameters containing :name (tool name) and :arguments (tool arguments)
   
   If a SchemaValidator is configured on the session (via set-params-validator) and
   the tool has an :inputSchema, arguments are validated before the handler is called.
   Invalid arguments produce an invalid-params error; the handler is never invoked.
   
   Returns the result of tool execution, or an error if the tool is not found
   or if argument validation fails."
  [rpc-session req-meta {:keys [name arguments] :as params}]
  (log/debug "Client requested tool execution - name:" name)
  (log/trace "Tool arguments:" arguments)

  (let [session @rpc-session]
    (if-let [tool (get-in session [::mcp/handlers :tools name])]
      (let [validator (::mcp/params-validator session)
            schema (:inputSchema tool)
            errors (when (and validator schema)
                     (try
                       (p/-validate validator (::mcp/serde session) schema (or arguments {}))
                       (catch Exception e
                         (log/warn e "Schema validator threw an exception for tool:" name)
                         [(str "Schema validation error: " (ex-message e))])))]
        (if errors
          (do (log/debug "Tool argument validation failed:" name errors)
              (c/invalid-params (str "Schema validation failed: " (str/join "; " errors))))
          (do (log/trace "Found tool handler, executing tool:" name)
              (-> ((:handler tool) (common/create-req-session rpc-session req-meta params) arguments)
                  (papply map->tool-message)))))
      (do
        (log/warn "Tool not found:" name)
        (log/trace "Available tools:" (keys (get-in session [::mcp/handlers :tools])))
        (c/invalid-params (format "Tool %s not found" name))))))

(defn add-tool-handlers [m]
  (assoc m "tools/list" (wrap-check-init tools-list) "tools/call" (wrap-check-init tools-call)))