(ns org.clojars.roklenarcic.mcp-server.handler.completions
  "This namespace handles completion-related MCP protocol operations.
  Completions provide auto-completion suggestions for various MCP entities
  such as resource URIs, prompt arguments, tool parameters, etc."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common]))

(defn do-completion
  "Processes a completion request by routing to the appropriate completion handler.
   
   This function implements the completion resolution strategy:
   1. First, try to find a specific completion handler for the ref type/name combination
   2. If not found, fall back to the general completion handler
   3. If no handlers are available, return an error
   
   Parameters:
   - rpc-session: the session atom
   - params: completion request parameters containing:
     - :ref: map with :type and :name identifying what to complete
     - :argument: map with :name and :value of the argument being completed
   
   Returns a completion response or error."
  [rpc-session req-meta {{:keys [name type]} :ref :keys [argument] :as params}]
  (log/trace "Processing completion request - type:" type "name:" name "argument:" (:name argument))
  (log/trace "Completion argument value:" (:value argument))
  
  ;; Try specific completion handler first
  (if-let [handler (get-in @rpc-session [::mcp/handlers :completions [type name]])]
    (do (log/trace "Found specific completion handler for" type "/" name)
        (handler (common/create-req-session rpc-session req-meta params) (:name argument) (:value argument)))
    (if-let [handler (get-in @rpc-session [::mcp/handlers :def-completion])]
      (do (log/trace "Using general completion handler for" type "/" name)
          (handler (common/create-req-session rpc-session req-meta params) type name (:name argument) (:value argument)))
      (do (log/info "No completion handler found for" type "/" name)
          (log/trace "Available specific completions:" (keys (get-in @rpc-session [::mcp/handlers :completions])))
          (log/trace "General completion handler available:" (some? (get-in @rpc-session [::mcp/handlers :def-completion])))
          (c/invalid-params (format "Completion %s/%s not found" type name))))))

(def handler (common/wrap-check-init do-completion))