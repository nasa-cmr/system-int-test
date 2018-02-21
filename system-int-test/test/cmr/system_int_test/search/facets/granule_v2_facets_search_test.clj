(ns cmr.system-int-test.search.facets.granule-v2-facets-search-test
  "This tests retrieving v2 facets when searching for granules."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.test :refer :all]
   [cmr.common-app.services.search.query-validation :as cqv]
   [cmr.common.util :as util]
   [cmr.system-int-test.data2.core :as d]
   [cmr.system-int-test.data2.granule :as dg]
   [cmr.system-int-test.data2.umm-spec-collection :as data-umm-c]
   [cmr.system-int-test.data2.umm-spec-common :as data-umm-cmn]
   [cmr.system-int-test.utils.index-util :as index]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.search-util :as search]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"}))

(defn- search-and-return-v2-facets
  "Returns only the facets from a v2 granule facets search request."
  [search-params]
  (let [query-params (merge {:page-size 0 :include-facets "v2"} search-params)]
    (get-in (search/find-concepts-json :granule query-params) [:results :facets])))

(deftest granule-facet-tests
  (testing (str "Granule facets are returned when requesting V2 facets in JSON format for a single"
                " collection concept ID.")
    (let [facets (search-and-return-v2-facets {:collection-concept-id "C1-PROV1"})]
      (is (= {:title "Browse Granules"
              :type "group"
              :has_children false}
             facets)))))

(defn- single-collection-test-setup
  "Ingests the collections and granules needed for the single collection validation test."
  []
  (let [coll1 (d/ingest-umm-spec-collection
               "PROV1" (data-umm-c/collection {:ShortName "SN1"
                                               :Version "V1"
                                               :EntryTitle "ET1"
                                               :concept-id "C1-PROV1"}))
        gran1 (d/item->concept
               (dg/granule-with-umm-spec-collection
                coll1 (:concept-id coll1) {:concept-id "G1-PROV1"}))
        coll2 (d/ingest-umm-spec-collection
               "PROV2" (data-umm-c/collection {:ShortName "SN2"
                                               :Version "V1"
                                               :EntryTitle "ET2"
                                               :concept-id "C1-PROV2"}))
        coll3 (d/ingest-umm-spec-collection
               "PROV2" (data-umm-c/collection {:ShortName "SN2"
                                               :Version "V2"
                                               :EntryTitle "ET3"
                                               :concept-id "C2-PROV2"}))
        gran2 (d/item->concept
               (dg/granule-with-umm-spec-collection
                coll2 (:concept-id coll2) {:concept-id "G1-PROV2"}))

        gran3 (d/item->concept
               (dg/granule-with-umm-spec-collection
                coll3 (:concept-id coll3) {:concept-id "G2-PROV2"}))]
    (ingest/ingest-concept gran1)
    (ingest/ingest-concept gran2)
    (ingest/ingest-concept gran3)
    (index/wait-until-indexed)))

(deftest single-collection-validation-tests
  (single-collection-test-setup)
  (testing "Allowed single collection queries"
    (util/are3
      [query-params]
      (let [response (search/find-concepts-json :granule (merge query-params
                                                                {:include-facets "v2"}))]
        (is (= 200 (:status response)))
        (is (= "Browse Granules" (get-in response [:results :facets :title]))))

      "Entry title" {:entry-title "ET1"}
      "Provider with single collection" {:provider "PROV1"}
      "Collection concept ID" {:collection-concept-id "C1-PROV1"}
      "ECHO Collection ID" {:echo-collection-id "C1-PROV1"}
      "Short name" {:short-name "SN1"}
      "Short name and version combination" {:short-name "SN2" :version "V1"}))

  (testing "Rejected multi-collection queries"
    (util/are3
      [query-params num-matched]
      (let [response (search/find-concepts-json :granule (merge query-params
                                                                {:include-facets "v2"}))]
        (is (= 400 (:status response)))
        (is (= [(str "Granule V2 facets are limited to a single collection, but query matched "
                     num-matched " collections.")]
               (:errors response))))

      "Multiple matched entry titles" {:entry-title ["ET1" "ET2"]} 2
      "Provider with multiple collections" {:provider "PROV2"} 2
      "Collection concept IDs" {:collection-concept-id ["C1-PROV1" "C1-PROV2" "C2-PROV2"]} 3
      "ECHO Collection IDs" {:echo-collection-id ["C1-PROV2" "C2-PROV2"]} 2
      "Short name with two matching collection versions" {:short-name "SN2"} 2
      "All granules query" {} "an undetermined number of")))

(deftest granule-facets-parameter-validation-tests
  (testing "Only the json format supports V2 granule facets."
    (let [xml-error-string (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?><errors><error>"
                                "V2 facets are only supported in the JSON format.</error></errors>")
          json-error-string "{\"errors\":[\"V2 facets are only supported in the JSON format.\"]}"]
      (doseq [fmt (cqv/supported-result-formats :granule)
              ;; timeline is not supported on the granule search route and json is valid, so we
              ;; do not test those.
              :when (not (#{:timeline :json} fmt))
              :let [expected-body (if (= :csv fmt) json-error-string xml-error-string)]]
        (testing (str "format" fmt)
          (let [response (search/find-concepts-in-format fmt :granule {:include-facets "v2"}
                                                         {:url-extension (name fmt)
                                                          :throw-exceptions false})]
            (is (= {:status 400
                    :body expected-body}
                   (select-keys response [:status :body]))))))))
  (testing "The value for include_facets must be v2 case insensitive."
    (testing "Case insensitive"
      (let [facets (get-in (search/find-concepts-json :granule {:include-facets "V2"
                                                                :collection-concept-id "C1-PROV1"})
                           [:results :facets])]
        (is (= "Browse Granules" (:title facets)))))
    (testing "Invalid values"
      (util/are3
        [param-value]
        (is (= {:status 400
                :errors [(str "Granule parameter include_facets only supports the value v2, but "
                              "was [" param-value "]")]}
               (search/find-concepts-json :granule {:include-facets param-value})))
        "foo" "foo"
        "V22" "V22"
        "true" true
        "false" false))))

(defn get-count-by-title
  "Returns the count for the given title for the provided facets."
  [facets title]
  (let [relevant-facet (first (filter #(= title (:title %)) facets))]
    (:count relevant-facet)))

(defn get-links
  "Returns the links from within the provided facets."
  [facets]
  (mapcat :links facets))

(defn traverse-link
  "Finds the link for the given facet title and navigates to that link, returning the response."
  [title facets]
  (let [facet-node (first (filter #(= title (:title %)) facets))
        [link-type url] (first (:links facet-node))]
    (is (some #{link-type} [:remove :apply]))
    (when url
      (client/get url))))

(defn assert-granules-match
  "Tests that the passed in expected granule matches the result returned in the JSON granule
  response."
  [expected-granules granule-json-response]
  (is (= (count expected-granules)
         (count granule-json-response)))
  (is (= (set (map :concept-id expected-granules))
         (set (map :id granule-json-response)))))

(defn get-link-type
  "Returns the link type for the given facet title and facets response."
  [title facets]
  (let [facet-node (first (filter #(= title (:title %)) facets))
        [link-type url] (first (:links facet-node))]
    link-type))

(deftest temporal-facets-test
  (let [coll (d/ingest-umm-spec-collection
              "PROV1"
              (data-umm-c/collection {:concept-id "C1-PROV1"
                                      :TemporalExtents
                                      [(data-umm-cmn/temporal-extent
                                        {:beginning-date-time "1970-01-01T00:00:00Z"})]}))
        gran2010-1 (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                      coll (:concept-id coll)
                                      {:granule-ur "Granule1"
                                       :beginning-date-time "2010-01-02T12:00:00Z"
                                       :ending-date-time "2010-01-11T12:00:00Z"}))
        gran2010-2 (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                      coll (:concept-id coll)
                                      {:granule-ur "Granule2"
                                       :beginning-date-time "2010-05-31T12:00:00Z"}))
        gran2010-3 (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                      coll (:concept-id coll)
                                      {:granule-ur "Granule2010-3"
                                       :beginning-date-time "2010-01-21T12:00:00Z"
                                       :ending-date-time "2020-01-11T12:00:00Z"}))
        gran2011-1 (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                      coll (:concept-id coll)
                                      {:granule-ur "Granule3"
                                       :beginning-date-time "2011-12-03T12:00:00Z"
                                       :ending-date-time "2011-12-20T12:00:00Z"}))
        gran1999-1 (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                      coll (:concept-id coll)
                                      {:granule-ur "Granule4"
                                       :beginning-date-time "1999-11-12T12:00:00Z"
                                       :ending-date-time "1999-12-03T12:00:00Z"}))
        gran2012-boundary (d/ingest "PROV1" (dg/granule-with-umm-spec-collection
                                             coll (:concept-id coll)
                                             {:granule-ur "Granule5"
                                              :beginning-date-time "2012-12-21T12:00:00Z"
                                              :ending-date-time "2013-03-01T12:00:00Z"}))]
    (index/wait-until-indexed)
    (let [root-node (search-and-return-v2-facets {:collection-concept-id "C1-PROV1"
                                                  :page_size 10})
          year-facets (-> root-node :children first :children first :children)
          temporal-node (-> root-node :children first)
          year-node (-> root-node :children first :children first)]
      (testing "Facet structure correct"
        (is (= "Browse Granules" (:title root-node)))
        (is (= "group" (:type root-node)))
        (is (= true (:has_children root-node)))
        (is (= "Temporal" (:title temporal-node)))
        (is (= "group" (:type temporal-node)))
        (is (= true (:has_children temporal-node)))
        (is (= "Year" (:title year-node)))
        (is (= true (:has_children year-node)))
        (is (= "group" (:type year-node))))
      (testing "Years returned in ascending order"
        (is (= ["1999" "2010" "2011" "2012"] (map :title year-facets))))
      (testing "Years have a type of filter"
        (is (= ["filter" "filter" "filter" "filter"] (map :type year-facets))))
      (testing "Counts correct"
        (util/are3
          [title cnt]
          (is (= cnt (get-count-by-title year-facets title)))
          "1999" "1999" 1
          "2010" "2010" 3
          "2011" "2011" 1
          "2012" "2012" 1))
      (testing "All of the years include a link to apply that year to the search."
        (let [links (mapcat :links year-facets)]
          (is (= 4 (count links)))
          (doseq [[link-type url] links]
            (is (= :apply link-type)))))
      (testing "When selecting a link the search results only contain granules for that year."
        (let [response (traverse-link "2010" year-facets)
              parsed-body (json/parse-string (:body response) true)
              updated-facets (get-in parsed-body [:feed :facets])
              granules-response (get-in parsed-body [:feed :entry])
              updated-year-facets (-> updated-facets :children first :children first :children)
              month-node (-> updated-facets :children first :children first :children first
                             :children first)
              month-facets (:children month-node)]
          (assert-granules-match [gran2010-1 gran2010-2 gran2010-3] granules-response)
          (is (= :remove (get-link-type "2010" updated-year-facets)))
          (testing "Selecting a year returns month facets for that year"
            (is (= "Month" (:title month-node)))
            (is (= ["01" "05"] (map :title month-facets)))
            (is (= [true true] (map :has_children month-facets)))
            (is (= :apply (get-link-type "01" month-facets)))
            (is (= :apply (get-link-type "05" month-facets)))
            (testing "Selecting a month returns only granules for that year and month."
              (let [response (traverse-link "01" month-facets)
                    parsed-body (json/parse-string (:body response) true)
                    updated-facets (get-in parsed-body [:feed :facets])
                    granules-response (get-in parsed-body [:feed :entry])
                    updated-year-facets (-> updated-facets :children first :children first
                                            :children)
                    updated-month-facets (-> updated-year-facets first :children first :children)
                    day-facets (-> updated-month-facets first :children first :children)]
                (assert-granules-match [gran2010-1 gran2010-3] granules-response)
                (is (= :remove (get-link-type "2010" updated-year-facets)))
                (is (= :remove (get-link-type "01" updated-month-facets)))
                (testing "Days are displayed below month facets when selecting a month."
                  (is (= ["02" "21"] (map :title day-facets)))
                  (is (= [false false] (map :has_children day-facets)))
                  (testing "Select a day returns only granules for that year, month, and day."
                    (let [response (traverse-link "21" day-facets)
                          parsed-body (json/parse-string (:body response) true)
                          updated-facets (get-in parsed-body [:feed :facets])
                          granules-response (get-in parsed-body [:feed :entry])
                          updated-year-facets (-> updated-facets :children first :children first
                                                  :children)
                          updated-month-facets (-> updated-year-facets first :children first :children)
                          updated-day-facets (-> updated-month-facets first :children first :children)]
                      (assert-granules-match [gran2010-3] granules-response)
                      (is (= :remove (get-link-type "2010" updated-year-facets)))
                      (is (= :remove (get-link-type "01" updated-month-facets)))
                      (is (= :remove (get-link-type "21" updated-day-facets)))
                      (testing (str "Navigating to a remove year link removes the search filter "
                                    "for year, month, and day.")
                        (let [;; Note that the test above traversed to 2010,
                              ;; so the 2010 link is now a remove link
                              response (traverse-link "2010" updated-year-facets)
                              parsed-body (json/parse-string (:body response) true)
                              updated-facets (get-in parsed-body [:feed :facets])
                              granules-response (get-in parsed-body [:feed :entry])
                              updated-year-facets (-> updated-facets :children first :children first :children)]
                          (assert-granules-match [gran1999-1 gran2010-1 gran2010-2 gran2010-3 gran2011-1
                                                  gran2012-boundary]
                                                 granules-response)
                          (is (= :apply (get-link-type "2010" updated-year-facets))))))))
                (testing "Navigating to a remove month link removes the search filter for that month."
                  (let [response (traverse-link "01" updated-month-facets)
                        parsed-body (json/parse-string (:body response) true)
                        updated-facets (get-in parsed-body [:feed :facets])
                        granules-response (get-in parsed-body [:feed :entry])
                        updated-year-facets (-> updated-facets :children first :children first :children)
                        updated-month-facets (-> updated-facets :children first :children first
                                                 :children first :children first :children)]
                    (assert-granules-match [gran2010-1 gran2010-2 gran2010-3] granules-response)
                    (is (= :apply (get-link-type "01" updated-month-facets)))
                    (is (= :apply (get-link-type "05" updated-month-facets)))))))))))))
