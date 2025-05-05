(ns org.clojars.roklenarcic.mcp-server.server.sse
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [ring.core.protocols :as ring])
  (:import (java.io IOException OutputStream)
           (java.util.concurrent ConcurrentLinkedQueue)))

(def streaming-headers {"Content-Type" "text/event-stream"
                        "Transfer-Encoding" "chunked"})

(defn cleanup-buffer
  "Remove expired requests in buffer."
  [^ConcurrentLinkedQueue q expire-ms]
  (let [iter (.iterator q)
        cut-off (- (System/currentTimeMillis) expire-ms)]
    (loop []
      (when (and (.hasNext iter) (< (first (.next iter)) cut-off))
        (.remove iter)
        (recur)))))

(defn close-os [session os] (swap! session update ::mcp/os #(if (= os %) nil %)))

(defn write-responses
  [session ^OutputStream os ^ConcurrentLinkedQueue q msg]
  (let [w (io/writer os)]
    (locking os
      (try
        (loop []
          (when-let [it (.poll q)]
            (try
              (.write w (str "data: " (second it) "\n\n"))
              (catch IOException e
                (.offer q it)
                (throw e)))
            (recur)))
        (when msg (.write w (str "data: " msg "\n\n")))
        (catch IOException e
          (log/debug e "Stream write didn't succeed")
          (close-os session os)
          ;; write to queue if unsuccessful send
          (when msg (.offer q [(System/currentTimeMillis) msg]))))
      (.flush w)
      nil)))

(defn send-to-client-fn
  [session client-req-timeout-ms]
  (fn [msg]
    (let [session-map @session]
      (cleanup-buffer (::mcp/q session-map) client-req-timeout-ms)
      (if-let [os (::mcp/os session-map)]
        (write-responses session os (::mcp/q session-map) msg)
        (when msg (.offer ^ConcurrentLinkedQueue (::mcp/q session-map) [(System/currentTimeMillis) msg]))))))

(defn get-resp [session sync? endpoint]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [this response os]
      (when endpoint
        (.write (io/writer os) (format "event: endpoint\ndata: %s" endpoint "\n\n")))
      (.flush ^OutputStream os)
      (swap! session assoc ::mcp/os os)
      (when sync? (close-os session os)))))

(defn post-resp [session resp sync?]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [this response os]
      ;; plug in our output stream if there's none in the session
      (let [new-sess (swap! session
                            (fn [session]
                              (if (::mcp/os session) session (assoc session ::mcp/os os))))]
        (log/debug "Handling message" resp)
        (write-responses session os (::mcp/q new-sess) resp)
        ;; if sync or if another OutputStream is available in the session (e.g. from GET request)
        ;; then just close our stream
        (when (or sync? (not= (::mcp/os new-sess) os))
          (close-os session os)
          (.close ^OutputStream os))))))