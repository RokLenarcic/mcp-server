(ns user (:import (java.util.logging Level Logger SimpleFormatter)))
(System/setProperty "java.util.logging.SimpleFormatter.format" "[%4$-7s] %3$s: %5$s %n")

(doto (Logger/getLogger "")
      (.setLevel Level/FINE)
      (-> .getHandlers first
          (doto (.setFormatter (SimpleFormatter.))
                (.setLevel Level/FINE))))

(println "Dev loaded")