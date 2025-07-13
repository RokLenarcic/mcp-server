(ns org.clojars.roklenarcic.mcp-server.json-rpc.parse
  (:require [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server.util :refer [map-of ?assoc]]))

(def ^:const PARSE_ERROR
  "JSON-RPC 2.0 error code for parse errors."
  -32700)

(def ^:const INVALID_REQUEST 
  "JSON-RPC 2.0 error code for invalid requests."
  -32600)

(def ^:const METHOD_NOT_FOUND 
  "JSON-RPC 2.0 error code for method not found."
  -32601)

(def ^:const INVALID_PARAMS 
  "JSON-RPC 2.0 error code for invalid parameters."
  -32602)

(def ^:const INTERNAL_ERROR 
  "JSON-RPC 2.0 error code for internal errors."
  -32603)

;; MCP-specific error codes
(def ^:const RESOURCE_NOT_FOUND 
  "MCP-specific error code for resource not found."
  -32002)

(defrecord Parsed [id result error method params item-type])

(defn ->request
  "Creates a parsed request message.
   
   Parameters:
   - method: method name string
   - params: method parameters (optional, defaults to nil)
   - id: request ID (string or number)
   
   Returns a Parsed record representing a request."
  ([method id] (->request method nil id))
  ([method params id]
   (let [item-type :request]
     (map->Parsed (map-of id method params item-type)))))

(defn ->notification
  "Creates a parsed notification message.
   
   Parameters:
   - method: method name string
   - params: method parameters (optional, defaults to nil)
   
   Returns a Parsed record representing a notification."
  ([method] (->notification method nil))
  ([method params]
   (let [item-type :notification]
     (map->Parsed (map-of method params item-type)))))

(defn ->error
  "Creates a parsed error message.
   
   Parameters:
   - error-code: numeric error code
   - message: error message string
   - data: additional error data (optional, defaults to nil)
   - id: request ID that caused the error
   
   Returns a Parsed record representing an error."
  ([error-code message id] (->error error-code message nil id))
  ([error-code message data id]
   (let [item-type :error
         error (?assoc {:code error-code :message message} :data data)]
     (map->Parsed (map-of error id item-type)))))

(defn parse-error
  "Creates a parse error message from an exception.
   
   Parameters:
   - e: exception that occurred during parsing
   
   Returns a Parsed record representing a parse error."
  [e]
  (->error PARSE_ERROR "Parse error" (ex-message e) nil))

(defn invalid-request
  "Creates an invalid request error message.
   
   Parameters:
   - data: error data (optional, defaults to nil)
   - id: request ID (optional, defaults to nil)
   
   Returns a Parsed record representing an invalid request error."
  ([id] (invalid-request nil id))
  ([data id]
   (->error INVALID_REQUEST "Invalid Request" data id)))

(defn parse-request
  "Parses and validates a JSON-RPC message object.
   
   Parameters:
   - msg: parsed JSON object representing a potential JSON-RPC message
   
   Returns a Parsed record or nil if the message is invalid."
  [{:keys [jsonrpc method id params result error] :as msg}]
  (log/trace "Parsing JSON-RPC message - method:" method "id:" id "has-result:" (some? result) "has-error:" (some? error))
  
  (cond
    (not= "2.0" jsonrpc)
    (do
      (log/debug "Invalid JSON-RPC version:" jsonrpc)
      (invalid-request "Wrong JSONRPC version" id))

    ;; Check if this is a client response (has result or error with id)
    (and id (or result error))
    (do
      (log/trace "Detected client response message")
      (->notification :client-resp {:error error :result result :id id}))

    ;; Validate request ID if present
    (not (or (string? id)
             (number? id)
             (nil? id)))
    (do
      (log/trace "Invalid request ID type:" (type id))
      (invalid-request (str "Invalid ID: " (pr-str id)) id))

    ;; Validate method name
    (not (string? method))
    (do
      (log/trace "Invalid method name:" method)
      (when id (invalid-request (str "Invalid Method: " (pr-str method)) id)))

    ;; Validate parameters if present
    (and (some? params) (not (or (vector? params) (map? params))))
    (do
      (log/trace "Invalid params type:" (type params))
      (when id (invalid-request (str "Invalid params: " (pr-str params)) id)))

    :else
    (if id (->request method params id) (->notification method params))))

(defn object->requests
  "Creates Parsed JSON-RPC message(s) from a parsed JSON payload.

   Handles both single messages and batches of messages.

   Parameters:
   - parsed-json: either a parsed JSON object/array or an Exception from parsing

   Returns a Parsed record, vector of Parsed records, or an error."
  [parsed-json]
  (if (vector? parsed-json)
    (if (empty? parsed-json)
      (invalid-request nil)
      (vec (keep parse-request parsed-json)))
    (if (instance? Exception parsed-json)
      (parse-error parsed-json)
      (parse-request parsed-json))))