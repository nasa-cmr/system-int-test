(ns cmr.system-int-test.search.granule-temporal-facet-search-test
  "Integration test for CMR granule temporal search"
  (:require
    [clojure.test :refer :all]
    [cmr.common.util :as util]
    [cmr.system-int-test.data2.core :as d]
    [cmr.system-int-test.data2.granule :as dg]
    [cmr.system-int-test.data2.umm-spec-collection :as data-umm-c]
    [cmr.system-int-test.data2.umm-spec-common :as data-umm-cmn]
    [cmr.system-int-test.utils.index-util :as index]
    [cmr.system-int-test.utils.ingest-util :as ingest]
    [cmr.system-int-test.utils.search-util :as search]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(defn- ingest-granule
  "Helper to ingest a granule"
  [provider collection granule-map]
  (d/ingest provider
            (dg/granule-with-umm-spec-collection collection (:concept-id collection) granule-map)))

(deftest search-by-temporal-facet
  (let [coll1 (d/ingest-umm-spec-collection
               "PROV1" (data-umm-c/collection {:TemporalExtents
                                                [(data-umm-cmn/temporal-extent
                                                  {:beginning-date-time "1370-01-01T00:00:00Z"})]}))
        ingest-granule-fn (partial ingest-granule "PROV1" coll1)
        gran1 (ingest-granule-fn {:granule-ur "Granule1"
                                  :beginning-date-time "1501-01-01T12:00:00Z"
                                  :ending-date-time "2010-01-11T12:00:00Z"})
        gran2 (ingest-granule-fn {:granule-ur "Granule2"
                                  :beginning-date-time "1888-01-31T12:00:00Z"
                                  :ending-date-time "2010-12-12T12:00:00Z"})
        gran3 (ingest-granule-fn {:granule-ur "Granule3"
                                  :beginning-date-time "1924-12-03T12:00:00Z"
                                  :ending-date-time "2010-12-20T12:00:00Z"})
        gran4 (ingest-granule-fn {:granule-ur "Granule4"
                                  :beginning-date-time "1924-12-12T12:00:00Z"
                                  :ending-date-time "2011-01-03T12:00:00Z"})
        gran5 (ingest-granule-fn {:granule-ur "Granule5"
                                  :beginning-date-time "1946-02-01T12:00:00Z"
                                  :ending-date-time "2011-03-01T12:00:00Z"})
        gran6 (ingest-granule-fn {:granule-ur "Granule6"
                                  :beginning-date-time "1952-01-30T12:00:00Z"})
        gran7 (ingest-granule-fn {:granule-ur "Granule7"
                                  :beginning-date-time "1983-12-12T12:00:00Z"})
        gran8 (ingest-granule-fn {:granule-ur "Granule8"
                                  :beginning-date-time "2011-12-13T12:00:00Z"})
        gran9 (ingest-granule-fn {:granule-ur "Granule9"})]
    (index/wait-until-indexed)

    (util/are3
      [params expected]
      (d/assert-refs-match expected (search/find-refs :granule params))

      "Finds single granule."
      {"temporal_facet[0][year]" 1501} [gran1]

      "Index does not need to start at 0."
      {"temporal_facet[1536][year]" 1501} [gran1]

      "Multiple grans in one year."
      {"temporal_facet[0][year]" 1924} [gran3 gran4]

      "Ending date is not matched against for temporal_facet searches."
      {"temporal_facet[0][year]" 2010} []

      "Multiple years are OR'ed together."
      {"temporal_facet[0][year]" 1501
       "temporal_facet[1][year]" 1924}
      [gran1 gran3 gran4]

      "Multiple years with non-consecutive indexes are OR'ed together."
      {"temporal_facet[0][year]" 1501
       "temporal_facet[3][year]" 1924
       "temporal_facet[11][year]" 1946}
      [gran1 gran3 gran4 gran5])))

;; TODO - figure out where the validation functions are going to go and then write unit tests
;; Just some symbolic invalid temporal testing, more complete test coverage is in unit tests
; (deftest search-temporal-facet-error-scenarios
;   (testing "search by invalid temporal format."
;     (let [{:keys [status errors]} (search/find-refs :granule {"temporal[]" "2010-13-12T12:00:00,"})]
;       (is (= 400 status))
;       (is (re-find #"temporal start datetime is invalid: \[2010-13-12T12:00:00\] is not a valid datetime" (first errors)))))
;   (testing "search by invalid temporal start-date after end-date."
;     (let [{:keys [status errors]} (search/find-refs :granule {"temporal[]" "2011-01-01T10:00:00Z,2010-01-10T12:00:00Z"})]
;       (is (= 400 status))
;       (is (re-find #"start_date \[2011-01-01T10:00:00Z\] must be before end_date \[2010-01-10T12:00:00Z\]" (first errors))))))
