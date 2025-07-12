(ns org.clojars.roklenarcic.mcp-server.util
  "This namespace provides utility functions used throughout the MCP server
   implementation. It includes helper functions for functional programming,
   map manipulation, async operations, and naming conventions."
  (:require [clojure.walk :refer [walk postwalk]])
  (:import (java.util.concurrent CompletableFuture)))

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

(defn camelCaseKey
  "Converts a kebab-case keyword to camelCase keyword.
   
   This function converts Clojure-style kebab-case keywords to JavaScript-style
   camelCase keywords, which is useful for JSON serialization.
   
   Parameters:
   - k: keyword to convert
   
   Returns a camelCase keyword.
   
   Examples:
   (camelCaseKey :hello-world) => :helloWorld
   (camelCaseKey :some-long-name) => :someLongName
   (camelCaseKey :simple) => :simple"
  [k]
  (let [sb (StringBuilder.)]
    (loop [[c & more] (name k)
           upper-next false]
      (if c
        (if (= c \-)
          (recur more true)
          (do (.append sb (if upper-next (Character/toUpperCase (char c)) ^Character c)) (recur more false)))
        (keyword (.toString sb))))))

(defn camelcase-keys
  "Recursively transforms all map keys from kebab-case to camelCase.
   
   This function walks through nested data structures and converts all map keys
   from Clojure-style kebab-case to JavaScript-style camelCase. This is useful
   when preparing data for JSON serialization.
   
   Parameters:
   - m: data structure to transform
   
   Returns the data structure with all map keys converted to camelCase.
   
   Examples:
   (camelcase-keys {:hello-world 1 :nested-map {:some-key 2}})
   => {:helloWorld 1 :nestedMap {:someKey 2}}"
  {:added "1.1"}
  [m]
  (let [f (fn [[k v]] [(camelCaseKey k) v])]
    ;; only apply to maps
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))
