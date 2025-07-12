(ns example.runner
  "This namespace provides a simple example runner that demonstrates how to
  start different MCP server examples. It acts as a dispatcher to launch
  various example servers based on command-line arguments or configuration."
  (:require [clojure.tools.logging :as log]
            [example.weather :as weather]))

;; =============================================================================
;; Example Server Dispatcher
;; =============================================================================
;; Main entry point for launching example MCP servers.
(defn start
  "Starts an example MCP server based on the provided configuration.

   This function acts as a dispatcher that launches different example servers
   depending on the :tool key in the argument map.

      Parameters:
      - arg-map: configuration map containing:
      - :tool: symbol indicating which example server to start

         Currently supported tools:
         - 'weather: starts the weather information MCP server

            Example:
            (start {:tool 'weather})"
  [arg-map]
  (log/info "Starting MCP server example with configuration:" arg-map)
  (case (:tool arg-map)
    'weather
    (do
      (log/info "Starting weather MCP server example")
      (weather/start))
    ; Default case - unknown tool
    (do
      (log/error "Unknown tool specified:" (:tool arg-map))
      (log/info "Available tools: weather")
      (throw (ex-info "Unknown tool" {:tool (:tool arg-map) :available ['weather]})))))