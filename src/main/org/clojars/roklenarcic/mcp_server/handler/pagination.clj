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
    (let [items-vec (vec items)
          start (if cursor
                  (let [idx (some (fn [[i item]]
                                    (when (= cursor (get item sort-key)) i))
                                  (map-indexed vector items-vec))]
                    (if idx (inc idx) 0))
                  0)
          n (count items-vec)
          page (subvec items-vec start (min (+ start page-size) n))
          next-cursor (when (< (+ start page-size) n)
                        (get (last page) sort-key))]
      {:items page :nextCursor next-cursor})))
