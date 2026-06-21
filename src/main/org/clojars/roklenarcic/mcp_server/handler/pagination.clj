(ns org.clojars.roklenarcic.mcp-server.handler.pagination
  "Cursor-based pagination utility for MCP list operations.")

(defn paginate
  "Paginate a collection of items using cursor-based pagination.

   Parameters:
   - items: a seq of maps, already sorted by sort-key
   - sort-key: keyword to extract the cursor value from each item (e.g. :name, :uri)
   - cursor: the cursor string from the client (or nil for first page)
   - page-size: positive int or nil. When nil, returns all items with nextCursor nil.

   Returns: {:items [...] :nextCursor <string-or-nil>}

   Behaviour:
   - When page-size is nil: returns all items, nextCursor nil.
   - When cursor is nil or stale: returns first page-size items.
   - When cursor matches a sort-key value: returns page-size items AFTER that item."
  [items sort-key cursor page-size]
  (if (nil? page-size)
    {:items (vec items) :nextCursor nil}
    (let [items (if cursor
                   (or (next (drop-while #(not= (get % sort-key) cursor) items))
                       items)
                   items)]
      (if (seq items)
        (loop [ret []
               i 1
               [x & more] items]
            (if (>= i page-size)
              {:items (conj ret x) :nextCursor (when more (get x sort-key))}
            (if more
              (recur (conj ret x) (inc i) more)
              {:items (conj ret x) :nextCursor nil})))
        {:items [] :nextCursor nil}))))
