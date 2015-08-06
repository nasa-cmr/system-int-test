(ns cmr.system-int-test.search.collection-concept-revision-search-test
  "Integration test for collection all revisions search"
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]
            [cmr.transmit.config :as transmit-config]
            [cmr.common.mime-types :as mt]
            [cmr.umm.core :as umm]
            [cmr.common.util :refer [are2] :as util]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"}))

(deftest search-collection-all-revisions
  (let [coll1-1 (d/ingest "PROV1" (dc/collection {:entry-title "et1"
                                                  :entry-id "eid1"
                                                  :version-id "v1"
                                                  :short-name "s1"}))
        concept1 {:provider-id "PROV1"
                  :concept-type :collection
                  :native-id (:entry-title coll1-1)}
        coll1-2-tombstone (merge (ingest/delete-concept concept1) concept1 {:deleted true})
        coll1-3 (d/ingest "PROV1" (dc/collection {:entry-title "et1"
                                                  :entry-id "eid1"
                                                  :version-id "v2"
                                                  :short-name "s1"}))

        coll2-1 (d/ingest "PROV1" (dc/collection {:entry-title "et2"
                                                  :entry-id "eid2"
                                                  :version-id "v1"
                                                  :short-name "s2"}))
        coll2-2 (d/ingest "PROV1" (dc/collection {:entry-title "et2"
                                                  :entry-id "eid2"
                                                  :version-id "v2"
                                                  :short-name "s2"}))
        concept2 {:provider-id "PROV1"
                  :concept-type :collection
                  :native-id (:entry-title coll2-2)}
        coll2-3-tombstone (merge (ingest/delete-concept concept2) concept2 {:deleted true})

        coll3 (d/ingest "PROV2" (dc/collection {:entry-title "et3"
                                                :entry-id "eid3"
                                                :version-id "v4"
                                                :short-name "s1"}))]
    (index/wait-until-indexed)
    (testing "find-references-with-all-revisions parameter"
      (are2 [collections params]
            (d/refs-match? collections (search/find-refs :collection params))

            ;; Should not get matching tombstone for second collection back
            "provider-id all-revisions=false"
            [coll1-3]
            {:provider-id "PROV1" :all-revisions false}

            "provider-id all-revisions unspecified"
            [coll1-3]
            {:provider-id "PROV1"}

            "provider-id all-revisions=true"
            [coll1-1 coll1-2-tombstone coll1-3 coll2-1 coll2-2 coll2-3-tombstone]
            {:provider-id "PROV1" :all-revisions true}

            "native-id all-revisions=false"
            [coll1-3]
            {:native-id "et1" :all-revisions false}

            "native-id all-revisions unspecified"
            [coll1-3]
            {:native-id "et1"}

            "native-id all-revisions=true"
            [coll1-1 coll1-2-tombstone coll1-3]
            {:native-id "et1" :all-revisions true}

            "version all-revisions=false"
            [coll1-3]
            {:version "v2" :all-revisions false}

            "version all-revisions unspecified"
            [coll1-3]
            {:version "v2"}

            "version all-revisions=true"
            [coll1-3 coll2-2 coll2-3-tombstone]
            {:version "v2" :all-revisions true}

            ;; verify that "finding latest", i.e., all-revisions=false, does not return old revisions
            "version all-revisions=false - no match to latest"
            []
            {:version "v1" :all-revisions false}

            "short-name all-revisions false"
            [coll1-3 coll3]
            {:short-name "s1" :all-revisions false}

            ;; this test is across providers
            "short-name all-revisions unspecified"
            [coll1-3 coll3]
            {:short-name "s1"}

            "short-name all-revisions true"
            [coll1-1 coll1-2-tombstone coll1-3 coll3]
            {:short-name "s1" :all-revisions true}

            "concept-id all-revisions false"
            [coll1-3]
            {:concept-id "C1200000000-PROV1" :all-revisions false}

            "concept-id all-revisions unspecified"
            [coll1-3]
            {:concept-id "C1200000000-PROV1"}

            "concept-id all-revisions true"
            [coll1-1 coll1-2-tombstone coll1-3]
            {:concept-id "C1200000000-PROV1" :all-revisions true}

            "all-revisions true"
            [coll1-1 coll1-2-tombstone coll1-3 coll2-1 coll2-2 coll2-3-tombstone coll3]
            {:all-revisions true}))))

(deftest search-all-revisions-error-cases
  (testing "collection search with all_revisions bad value"
    (let [{:keys [status errors]} (search/find-refs :collection {:all-revisions "foo"})]
      (is (= [400 ["Parameter all_revisions must take value of true, false, or unset, but was [foo]"]]
             [status errors]))))
  (testing "granule search with all_revisions parameter is not supported"
    (let [{:keys [status errors]} (search/find-refs :granule {:provider-id "PROV1"
                                                              :all-revisions false})]
      (is (= [400 ["Parameter [all_revisions] was not recognized."]]
             [status errors]))))
  (testing "granule search with all_revisions bad value"
    (let [{:keys [status errors]} (search/find-refs :granule {:provider-id "PROV1"
                                                              :all-revisions "foo"})]
      (is (= [400 ["Parameter [all_revisions] was not recognized."
                   "Parameter all_revisions must take value of true, false, or unset, but was [foo]"]]
             [status errors])))))

