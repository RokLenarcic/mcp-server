(ns org.clojars.roklenarcic.mcp-server.handler-pagination-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.mcp-server.handler.pagination :as pagination]))

(def items-5
  [{:name "apple"}
   {:name "banana"}
   {:name "cherry"}
   {:name "date"}
   {:name "elderberry"}])

(deftest paginate-nil-page-size-test
  (testing "nil page-size returns all items, nextCursor nil"
    (is (= {:items items-5 :nextCursor nil}
           (pagination/paginate items-5 :name nil nil)))
    (is (= {:items items-5 :nextCursor nil}
           (pagination/paginate items-5 :name "banana" nil)))))

(deftest paginate-full-traversal-test
  (testing "page-size 2 traverses all 5 items in three calls"
    (let [p1 (pagination/paginate items-5 :name nil 2)
          p2 (pagination/paginate items-5 :name (:nextCursor p1) 2)
          p3 (pagination/paginate items-5 :name (:nextCursor p2) 2)]
      (is (= {:items [{:name "apple"} {:name "banana"}] :nextCursor "banana"}   p1))
      (is (= {:items [{:name "cherry"} {:name "date"}]  :nextCursor "date"}     p2))
      (is (= {:items [{:name "elderberry"}]             :nextCursor nil}         p3)))))

(deftest paginate-edge-cases-test
  (testing "stale/unknown cursor returns first page"
    (is (= {:items [{:name "apple"} {:name "banana"}] :nextCursor "banana"}
           (pagination/paginate items-5 :name "zzz-unknown" 2))))

  (testing "empty collection"
    (are [cursor page-size]
      (= {:items [] :nextCursor nil}
         (pagination/paginate [] :name cursor page-size))
      nil  2
      nil  nil
      "x"  2))

  (testing "page-size larger than collection returns all items, no nextCursor"
    (is (= {:items items-5 :nextCursor nil}
           (pagination/paginate items-5 :name nil 100))))

  (testing "single-item collection with page-size 1 returns item, no nextCursor"
    (is (= {:items [{:name "only"}] :nextCursor nil}
           (pagination/paginate [{:name "only"}] :name nil 1)))))
