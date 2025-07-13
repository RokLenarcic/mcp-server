(ns example.rainfall
  (:require [org.clojars.roklenarcic.mcp-server.json.charred :as json]
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
      (http/ring-handler session (http/memory-sessions-store) {:endpoint "http://localhost:5556/sse"})
      {:port 5556 })))

(defn post-to-http-client [id method params]
  (client/post "http://localhost:5556/sse?sessionId=sss"
               {:content-type :json
                :form-params {:jsonrpc "2.0" :id id :method method :params params}}))

(comment

  (future (start)))