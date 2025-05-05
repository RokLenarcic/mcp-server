(ns org.clojars.roklenarcic.mcp-server.server.streams
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply pcatch]])
  (:import (java.io BufferedReader InputStream OutputStream)))

(defn process [session str-msg]
  (try
    (let [{::mcp/keys [dispatch-table serde]} @session
          parsed (rpc/parse-string str-msg serde)
          handle #(rpc/handle-parsed % dispatch-table session)]
      (pcatch (if (sequential? parsed)
                (->> (keep handle parsed)
                     rpc/combine-futures)
                (handle parsed))
              #(do (log/error %)
                   (rpc/make-response (c/internal-error nil (ex-message %)) nil))))
    (catch Exception e
      (log/error e)
      (rpc/make-response (c/internal-error nil (ex-message e)) nil))))

(defn run
  "Starts running the server using input and output stream provided. Interrupting
   the thread will cause the server to stop. Closing the input stream also stops the process."
  [session ^InputStream is {:keys [client-req-timeout-ms]}]
  (let [^BufferedReader r (io/reader is)]
    (try
      (loop []
        (if-let [msg (.readLine r)]
          (do (rpc/cleanup-requests (or client-req-timeout-ms 120000))
              (papply (process session msg)
                      #(some->> %
                                (rpc/json-serialize (::mcp/serde @session))
                                ((::mcp/send-to-client @session))))
              (recur))
          (do (log/info "EOF reached, stopping server")
              (swap! session dissoc ::mcp/os))))
      (catch InterruptedException _
        (log/info "Stream server interrupted, stopping...")))))

(defn create-session
  "Create a session from the template and output stream."
  [session-template ^OutputStream os]
  (let [write-string-fn #(do (log/debug "Responding: " %)
                           (locking os
                             (doto (io/writer os)
                               (.write ^String %)
                               (.write (int \newline))
                               (.flush))))]
    (add-watch (atom (assoc @session-template
                       ::mcp/send-to-client write-string-fn
                       ::mcp/os os))
               ::watcher
               handler/change-watcher)))
