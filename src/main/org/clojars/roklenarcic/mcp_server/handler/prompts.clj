(ns org.clojars.roklenarcic.mcp-server.handler.prompts
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

(defn ->messages [resp]
  (cond
    (satisfies? p/PromptResponse resp) (->messages (p/-prompt-msgs resp))
    (or (satisfies? p/Message resp)
        (satisfies? p/Content resp)
        (satisfies? p/ResourceResponse resp)) [resp]
    :else resp))

(defn get-prompt-result [resp]
  (if (instance? JSONRPCError resp)
    resp
    (let [description (when (satisfies? p/PromptResponse resp) (p/-prompt-desc resp))
          messages (->messages resp)]
      {:description description
       :messages (mapv common/proto->message messages)})))

(defn prompts-list [rpc-session _]
  (log/debug "Prompt list requested")
  (let [prompts (-> @rpc-session ::mcp/handlers :prompts vals)]
    (log/debug "Prompt list returned" prompts)
    {:prompts (or (mapv #(dissoc % :handler) prompts) [])}))

(defn prompts-get [rpc-session {:keys [name arguments]}]
  (log/debug "Invoking prompt" name "with arguments" arguments)
  (if-let [prompt-handler (get-in @rpc-session [::mcp/handlers :prompts name :handler])]
    (-> (prompt-handler (common/create-req-session rpc-session) arguments)
        (papply get-prompt-result))
    (do (log/debug "prompt" name "not found")
        (c/invalid-params (format "prompt %s not found" name)))))

(defn add-prompt-handlers [m]
  (assoc m "prompts/list" (wrap-check-init prompts-list)
           "prompts/get" (wrap-check-init prompts-get)))