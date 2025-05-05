(ns example.runner
  (:require [example.weather :as weather]))

(defn start [arg-map]
  (case (:tool arg-map)
    'weather (weather/start)))