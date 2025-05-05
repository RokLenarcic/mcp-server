(ns example.weather
  (:require [org.clojars.roklenarcic.mcp-server.json.charred :as json]
            [org.clojars.roklenarcic.mcp-server.server :as server]))

(def server-info
  (server/server-info "Weather Service"
                      "1.0.0"
                      "This service provides various weather data"))

(defn get-weather [exchange {:keys [location]}]
  (format "Weather at location %s is %s, temperature is %s degrees"
          location
          (rand-nth ["sunny" "cloudy" "snowing" "rainy" "hailstorm"])
          (- 20 (rand-int 60))))

(def tool (server/tool
            "get_current_weather"
            "Reports current weather based on the location"
            (server/obj-schema nil
                               {:location (server/str-schema "Location of interest" nil)}
                               ["location"])
            get-weather))

(defn start []
  ;; create primordial session
  (let [session (-> (server/make-session
                      ;; plug in some general server config
                      server-info
                      ;; pick a JSON serialization implementation
                      (json/serde {})
                      ;; additional context in the sessions
                      {})
                    ;; add a tool to that
                    (server/add-tool tool))]
    ;; start a STDIO server
    (server/start-server-on-streams session System/in System/out {})))