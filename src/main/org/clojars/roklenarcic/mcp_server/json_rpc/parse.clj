(ns org.clojars.roklenarcic.mcp-server.json-rpc.parse
  (:require [org.clojars.roklenarcic.mcp-server.util :refer [map-of ?assoc]]))

;; JSON-RPC 2.0 Error Codes
(def ^:const PARSE_ERROR -32700)
(def ^:const INVALID_REQUEST -32600)
(def ^:const METHOD_NOT_FOUND -32601)
(def ^:const INVALID_PARAMS -32602)
(def ^:const INTERNAL_ERROR -32603)

(def ^:const RESOURCE_NOT_FOUND -32002)

(defrecord Parsed [id result error method params item-type])

(defn ->request
  "Create a JSON-RPC request map"
  ([method id] (->request method nil id))
  ([method params id]
   (let [item-type :request]
     (map->Parsed (map-of id method params item-type)))))

(defn ->notification
  "Create a JSON-RPC notification map (request without id)"
  ([method] (->notification method nil))
  ([method params]
   (let [item-type :notification]
     (map->Parsed (map-of method params item-type)))))

(defn ->error
  "Create a JSON-RPC error response map"
  ([error-code message id] (->error error-code message nil id))
  ([error-code message data id]
   (let [item-type :error
         error (?assoc {:code error-code :message message} :data data)]
     (map->Parsed (map-of error id item-type)))))

(defn parse-error
  [e]
  (->error PARSE_ERROR "Parse error" (ex-message e) nil))

(defn invalid-request
  "Create an invalid request error response"
  ([id] (invalid-request nil id))
  ([data id]
   (->error INVALID_REQUEST "Invalid Request" data id)))

(defn parse-request
  "Parses a Request/Notification from the msg, potentially returns JSONRPCError"
  [{:keys [jsonrpc method id params result error] :as msg}]
  (cond
    ;; Check for required jsonrpc version
    (not= "2.0" jsonrpc)
    (invalid-request "Wrong JSONRPC version" id)

    (and id (or result error))
    (->notification :client-resp {:error error :result result :id id})

    ;; Check id if present
    (not (or (string? id)
             (number? id)
             (nil? id)))
    (invalid-request id)

    ;; Check for required method field
    (not (string? method))
    (when id (invalid-request (str "Invalid Method: " (pr-str method)) id))

    ;; Check params if present
    (and (some? params) (not (or (vector? params) (map? params))))
    (when id (invalid-request (str "Invalid params: " (pr-str params)) id))

    ;; Valid request or notification
    :else
    (if id (->request method params id) (->notification method params))))

(defn parse-string
  "Returns a parsed object or a batch of them.

  Handles batches and serialization, deserialization."
  [parsed-json]
  (if (vector? parsed-json)
    (if (empty? parsed-json)
      (invalid-request nil)
      (vec (keep parse-request parsed-json)))
    (if (instance? Exception parsed-json)
      (parse-error parsed-json)
      (parse-request parsed-json))))