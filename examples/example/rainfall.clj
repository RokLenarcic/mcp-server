(ns example.rainfall
  (:require [clj-http.client :as client]
            [org.clojars.roklenarcic.mcp-server.json.charred :as json]
            [ring.adapter.jetty :refer [run-jetty]]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.server.http :as http]))

(def server-info
  (server/server-info "Rainfall Service"
                      "1.0.0"
                      "This service provides data about rainfall based on location"))

(defn get-rainfall [exchange {:keys [location]}]
  (format "Last month the rainfall at location %s is %smm" location (rand-int 120)))

(def tool (server/tool
            "get_rainfall"
            "Reports last month's rainfall based on the location"
            (server/obj-schema nil
                               {:location (server/str-schema "Location of interest" nil)}
                               ["location"])
            get-rainfall))

(defn start []
  (let [session (-> (server/make-session
                      ;; plug in some general server config
                      server-info
                      ;; pick a JSON serialization implementation
                      (json/serde {})
                      ;; additional context in the sessions
                      {})
                    ;; add a tool to that
                    (server/add-tool tool))]
    (run-jetty
      (http/ring-handler session (http/memory-sessions-store) {})
      {:port 5556})))

(defn post-to-http-client
  "Send a JSON-RPC request to the running server. The first call should be an
  `initialize` request without a session id; the response carries an
  `Mcp-Session-Id` header that subsequent calls must include."
  ([id method params]
   (post-to-http-client id method params nil))
  ([id method params session-id]
   (client/post "http://localhost:5556/"
                {:content-type :json
                 :accept "application/json, text/event-stream"
                 :headers (cond-> {"Origin" "http://localhost:5556"}
                            session-id (assoc "Mcp-Session-Id" session-id))
                 :form-params {:jsonrpc "2.0" :id id :method method :params params}})))

(comment

  (future (start)))