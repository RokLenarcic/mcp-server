(ns org.clojars.roklenarcic.mcp-server.server.http-test
  (:require [clojure.test :refer :all]
            [example.rainfall :as rainfall]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [charred.api :as json]
            [clj-http.client :as client]))

(defn start []
  (future (rainfall/start)))

(defn post-to-http-client [id method params]
  (client/post "http://localhost:5556/sse?sessionId=sss"
               {:content-type :json
                :form-params {:jsonrpc "2.0" :id id :method method :params params}}))