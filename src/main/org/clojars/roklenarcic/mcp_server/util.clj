(ns org.clojars.roklenarcic.mcp-server.util
  (:require [clojure.walk :refer [walk postwalk]])
  (:import (java.util.concurrent CompletableFuture)))

(defn ?!
  "Run if the argument is a fn. This function can accept a value or function. If it is a
  function then it will apply the remaining arguments to it; otherwise it will just return
  `v`."
  [v & args]
  (if (fn? v)
    (apply v args)
    v))

(defn- ->sym
  "Extract a symbol from the form.

  They symbol extracted will be:
  - the first symbol not in function name function call position.
  - the first keyword in function call position

  e.g. (->sym '(1 23 (inc {:a a}))) -> 'a'
       (->sym '(:x y)) -> 'x'
       (->sym '(inc y)) -> 'y'"
  [form]
  (walk ->sym
        #(if (coll? %) (first (filter symbol? %)) (when (symbol? %) %))
        (cond (and (list? form) (symbol? (first form))) (rest form)
              (and (list? form) (keyword? (first form))) (symbol (name (first form)))
              (map? form) (mapcat identity form)
              :else form)))

(defmacro map-of
  "Creates map with symbol names as keywords as keys and
   symbol values as values.

   Example: (map-of id name) => {:id id :name name}"
  [& syms]
  `(zipmap ~(vec (map (comp keyword ->sym) syms)) ~(vec syms)))

(defn ?assoc
  ([m k v] (if (nil? v) m (assoc m k v)))
  ([m k v & kvs]
   (let [ret (?assoc m k v)]
     (if kvs
       (if (next kvs)
         (recur ret (first kvs) (second kvs) (nnext kvs))
         (throw (IllegalArgumentException.
                  "?assoc expects even number of arguments after map/vector, found odd number")))
       ret))))

(defn papply
  "Apply f to CompletableFuture or a non-future value"
  [p f & args]
  (if (instance? CompletableFuture p)
    (.thenApply ^CompletableFuture p #(apply f % args))
    (apply f p args)))

(defn pcatch
  "Adds catch handler to CompletableFuture, non-future value is left alone."
  [p f & args]
  (if (instance? CompletableFuture p)
    (.exceptionally ^CompletableFuture p #(apply f % args))
    p))

(def runtime-vthreads?
  (memoize
    (fn []
      (and (not clojure.core/*compile-files*)
           (try
             (Class/forName "java.lang.Thread$Builder$OfVirtual")
             true
             (catch ClassNotFoundException _
               false))))))

(defn camelCaseKey [k]
  (let [sb (StringBuilder.)]
    (loop [[c & more] (name k)
           upper-next false]
      (if c
        (if (= c \-)
          (recur more true)
          (do (.append sb (if upper-next (Character/toUpperCase (char c)) ^Character c)) (recur more false)))
        (keyword (.toString sb))))))

(defn camelcase-keys
  "Recursively transforms all map keys to camel case."
  {:added "1.1"}
  [m]
  (let [f (fn [[k v]] [(camelCaseKey k) v])]
    ;; only apply to maps
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))
