(ns user
  (:require [clojure.tools.logging :as logging])
  (:import (java.util.logging Level Logger SimpleFormatter)))
(System/setProperty "java.util.logging.SimpleFormatter.format" "[%4$-7s] %3$s: %5$s %n")

(alter-var-root #'logging/*logger-factory*
                (fn [_] (clojure.tools.logging.impl/jul-factory)))

(doto (Logger/getLogger "")
      (.setLevel Level/FINEST)
      (-> .getHandlers first
          (doto (.setFormatter (SimpleFormatter.))
                (.setLevel Level/FINEST))))

(println "Dev loaded")