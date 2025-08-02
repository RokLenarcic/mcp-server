(ns org.clojars.roklenarcic.mcp-server.test-inputs
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [charred.api :as json]
            [clojure.tools.logging :as log]
            [org.clojars.roklenarcic.mcp-server :as-alias mcp]
            [org.clojars.roklenarcic.mcp-server.handler.init :as init]
    #_[org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.json.charred :as charred]
            [org.clojars.roklenarcic.mcp-server.json.cheshire :as cheshire]
            [org.clojars.roklenarcic.mcp-server.server :as server]
            [org.clojars.roklenarcic.mcp-server.server.streams :as streams])
  (:import (clojure.lang IFn)
           (java.io BufferedReader InputStream PipedInputStream PipedOutputStream)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn read-bytes [q arr off len]
  (if @q
    (loop [i 0]
      (if-let [q (and (not= i len) @q)]
        (if-let [c (.poll q 50 TimeUnit/MILLISECONDS)]
          (do (aset arr (+ off i) c)
              (recur (inc i)))
          (if (zero? i) (recur i) i))
        i))
    -1))

(defn input-seq []
  (let [q (atom (LinkedBlockingQueue.))]
    (proxy [InputStream IFn] []
      (read
        ([] (if-let [q @q] (.take q) -1))
        ([^bytes bytes]
         (read-bytes q bytes 0 (alength bytes)))
        ([^bytes bytes off len] (read-bytes q bytes off len)))
      (close [] (reset! q nil))
      (invoke [x] (when-let [q @q] (doseq [c (.getBytes x)] (.put q c)))))))

(defonce ids (atom 0))

(defn print-notif [w method params]
  (doto w
    (.write (json/write-json-str {:jsonrpc "2.0" :method method :params params}))
    (.write "\n")
    (.flush)))

(defn print-req
  ([w method params]
   (print-req w method params (swap! ids inc)))
  ([w method params id]
   (doto w
     (.write (json/write-json-str {:jsonrpc "2.0" :method method :params params :id id}))
     (.write "\n")
     (.flush))))

(defn create-server
  []
  (let [is (PipedInputStream.)
        os (PipedOutputStream. is)

        os2 (PipedOutputStream.)
        out (PipedInputStream. os2)
        s (streams/create-session (server/make-session
                                    (server/server-info "Test MCP Server" "1.0.0" "Instructions on how to use this server" true)
                                    ;(charred/serde {})
                                    (cheshire/serde {})
                                    {})
                                  os2)
        stdin (io/writer os)
        ^BufferedReader stdout (io/reader out)]
    (future (try (streams/run s is {})
                 (catch Exception e
                   (log/error e))))
    {:server s
     :stdin stdin
     :stdout stdout}))

(defn initialize [{:keys [server stdin stdout]}]
  (print-req stdin "initialize"
             {:protocolVersion init/server-protocol-version
              :capabilities {:roots {} :sampling {}}
              :clientInfo {:name "Test Client" :version "1.0.0"}})

  (.readLine stdout)

  (print-notif stdin "notifications/initialized" nil)

  (loop [i 0]
    (when (and (not (::mcp/initialized? @server))
               (<= i 100))
      (Thread/sleep 20))))

(defn get-req-and-resp
  "Gets a request from the server and responds, with supplied result, returns request."
  [{:keys [stdin stdout]} result]
  (let [r (json/read-json (.readLine stdout))]
    (doto stdin
      (.write (json/write-json-str {:jsonrpc "2.0" :result result :id (get r "id")}))
      (.write "\n")
      (.flush))
    r))