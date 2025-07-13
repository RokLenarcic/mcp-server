(ns org.clojars.roklenarcic.mcp-server.handler.prompts
  "This namespace handles prompt-related MCP protocol operations.
  Prompts are reusable message templates that can be parameterized and
  invoked by clients. This handler manages prompt registration, listing, and execution."
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.protocol :as p]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply camelcase-keys]]
            [org.clojars.roklenarcic.mcp-server.handler.common :as common :refer [wrap-check-init]])
  (:import (org.clojars.roklenarcic.mcp_server.core JSONRPCError)))

(defn ->prompt
  "Preprocess spec into shape that corresponds to what we need to return on list request."
  [{:keys [required-args optional-args] :as all}]
  (let [ra (reduce-kv (fn [acc name description]
                        (conj acc {:name name :description description :required true}))
                      []
                      required-args)
        oa (reduce-kv (fn [acc name description]
                        (conj acc {:name name :description description :required false}))
                      []
                      optional-args)]
    (-> (dissoc all :required-args :optional-args)
        (assoc :arguments (into ra oa))
        (camelcase-keys))))

(defn ->messages
  "Extracts messages from a prompt response.
   
   Parameters:
   - resp: prompt response (PromptResponse, Message, Content, ResourceResponse, or collection)
   
   Returns a collection of message objects."
  [resp]
  (log/trace "Extracting messages from response type:" (type resp))
  (cond
    (satisfies? p/PromptResponse resp) (->messages (p/-prompt-msgs resp))
    (or (satisfies? p/Message resp)
        (satisfies? p/Content resp)
        (satisfies? p/ResourceResponse resp)) [resp]
    :else resp))

(defn get-prompt-result 
  "Converts a prompt execution result to MCP wire format.
   
   Parameters:
   - resp: prompt execution result (PromptResponse, JSONRPCError, or messages)
   
   Returns a map in MCP prompt response format with :description and :messages keys."
  [resp]
  (if (instance? JSONRPCError resp)
    resp
    (let [description (when (satisfies? p/PromptResponse resp) (p/-prompt-desc resp))
          messages (->messages resp)]
      (log/trace "Creating prompt result with" (count messages) "messages")
      {:description description
       :messages (mapv common/proto->message messages)})))

(defn prompts-list
  "Handles prompts/list requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - _: unused parameters
   
   Returns a map with :prompts key containing the list of available prompts."
  [rpc-session _]
  (log/trace "Client requested prompt list")
  (let [prompts (-> @rpc-session ::mcp/handlers :prompts vals)]
    (log/debug "Returning" (count prompts) "prompts:" (mapv :name prompts))
    {:prompts (or (mapv #(dissoc % :handler) prompts) [])}))

(defn prompts-get 
  "Handles prompts/get requests from the client.
   
   Parameters:
   - rpc-session: the session atom
   - params: request parameters containing :name (prompt name) and :arguments (prompt arguments)
   
   Returns the result of prompt execution, or an error if the prompt is not found."
  [rpc-session {:keys [name arguments]}]
  (log/debug "Client requested prompt execution - name:" name)
  (log/trace "Prompt arguments:" arguments)
  
  (if-let [prompt-handler (get-in @rpc-session [::mcp/handlers :prompts name :handler])]
    (do
      (log/trace "Found prompt handler, executing prompt:" name)
      (-> (prompt-handler (common/create-req-session rpc-session) arguments)
          (papply get-prompt-result)))
    (do (log/info "Prompt not found:" name)
        (log/trace "Available prompts:" (keys (get-in @rpc-session [::mcp/handlers :prompts])))
        (c/invalid-params (format "Prompt %s not found" name)))))

(defn add-prompt-handlers [m]
  (assoc m "prompts/list" (wrap-check-init prompts-list)
           "prompts/get" (wrap-check-init prompts-get)))