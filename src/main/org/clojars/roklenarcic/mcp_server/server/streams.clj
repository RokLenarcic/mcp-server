(ns org.clojars.roklenarcic.mcp-server.server.streams
  "This namespace handles the stream-based communication layer for the MCP server.
   It manages reading JSON-RPC messages from input streams and writing responses
   to output streams, providing the main server execution loop.

   The caller owns the lifecycle of the input and output streams; this
   namespace does not close them. When `run` returns the caller can close
   the streams (or reuse them for another session)."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.core :as c]
            [org.clojars.roklenarcic.mcp-server.json-rpc :as rpc]
            [org.clojars.roklenarcic.mcp-server.handler.common :as handler]
            [org.clojars.roklenarcic.mcp-server.util :refer [papply pcatch]])
  (:import (java.io BufferedReader InputStream OutputStream)
           (java.nio.channels ClosedByInterruptException)))

(defn process
  "Processes a single JSON-RPC message string.
   
   Parameters:
   - session: the session atom containing server state
   - str-msg: the JSON-RPC message string to process
   
   Returns a JSON-RPC response object or CompletableFuture, or nil for notifications."
  [session str-msg]
  (try
    (let [{::mcp/keys [dispatch-table serde]} @session]
      (log/trace "Processing message of length:" (count str-msg))
      (pcatch (rpc/handle-parsed (rpc/parse-string str-msg serde) dispatch-table session nil)
              (fn [e]
                (log/error e "Error during message processing")
                (rpc/make-response (c/internal-error nil (ex-message e)) nil))))
    (catch Exception e
      (log/error e "Error during message processing")
      (rpc/make-response (c/internal-error nil (ex-message e)) nil))))

(defn- await-in-flight
  "Blocks until there are no in-flight requests on the session.

   The current thread's interrupt flag is cleared before sleeping so this
   helper can run after a `ClosedByInterruptException` (which leaves the
   flag set). A subsequent interrupt during the wait aborts the wait and
   abandons any remaining in-flight requests."
  [session]
  (Thread/interrupted) ;; clear interrupt flag if set
  (try
    (while (seq (::mcp/in-flight @session))
      (Thread/sleep 400)
      (log/infof "Awaiting %d outstanding requests %s"
                 (count (::mcp/in-flight @session))
                 (::mcp/in-flight @session)))
    (catch InterruptedException _
      (log/infof "Interrupted during shutdown wait; abandoning %d in-flight requests"
                 (count (::mcp/in-flight @session))))))

(defn run
  "Runs the MCP server using the provided input stream for reading JSON-RPC messages.
   
   This function implements the main server loop that:
   1. Reads line-by-line from the input stream
   2. Processes each JSON-RPC message
   3. Sends responses back to the client
   4. Handles client request timeouts
   
   Parameters:
   - session: the session atom containing server state and configuration
   - is: InputStream to read JSON-RPC messages from
   - opts: options map containing:
     - :client-req-timeout-ms: timeout in milliseconds for client requests (default: 120000)
   
   The server will continue running until:
   - The input stream is closed (EOF reached)
   - The thread is interrupted
   - An unrecoverable error occurs

   On shutdown the loop waits for in-flight requests to drain (subject to
   a further interrupt) before returning. The input and output streams are
   not closed; the caller owns their lifecycle.

   The function blocks until the server stops."
  [session ^InputStream is {:keys [client-req-timeout-ms]}]
  (let [^BufferedReader r (io/reader is)
        timeout-ms (or client-req-timeout-ms 120000)]
    (log/debug "Starting MCP server main loop with timeout:" timeout-ms "ms")
    (try
      (loop []
        (if-let [msg (.readLine r)]
          (do (rpc/cleanup-requests timeout-ms)
              (papply (process session msg)
                      (fn [response]
                        (when response
                          (let [json-response (rpc/json-serialize (::mcp/serde @session) response)]
                            (log/trace "Sending response of length:" (count json-response))
                            ((::mcp/send-to-client @session) json-response)))))
              (recur))
          (log/info "EOF reached, stopping server")))
      (catch InterruptedException _
        (log/info "Stream server interrupted, stopping..."))
      (catch ClosedByInterruptException _
        (log/info "Stream server interrupted (channel closed), stopping..."))
      (finally
        (await-in-flight session)))))

(defn create-session
  "Creates a session from a template and configures it for stream communication.

   The output stream is captured by the `send-to-client` closure but is not
   stored in the session map; the streams transport keeps a single,
   caller-owned output stream for the lifetime of the session, so there is
   no rotation to manage and the watcher's stream-close logic does not
   apply here.

   Parameters:
   - session-template: the base session atom to use as a template
   - os: OutputStream to write JSON-RPC responses to

   Returns a new session atom"
  [session-template ^OutputStream os]
  (let [write-string-fn (fn [json-str]
                          (log/trace "Writing response to stream:" json-str)
                          (locking os  ; Ensure thread-safe writes
                            (doto (io/writer os)
                              (.write ^String json-str)
                              (.write (int \newline))
                              (.flush))))]
    (-> (assoc @session-template
          ::mcp/send-to-client write-string-fn)
        atom
        (add-watch ::watcher handler/change-watcher))))
