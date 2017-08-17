(ns cmr.system-int-test.bootstrap.bulk-index.validation-test
  "Integration test for CMR bulk index validation operations."
  (:require
   [clojure.test :refer :all]
   [cmr.mock-echo.client.echo-util :as e]
   [cmr.system-int-test.bootstrap.bulk-index.core :as core]
   [cmr.system-int-test.data2.collection :as dc]
   [cmr.system-int-test.data2.granule :as dg]
   [cmr.system-int-test.system :as s]
   [cmr.system-int-test.utils.bootstrap-util :as bootstrap]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.metadata-db-util :as mdb]
   [cmr.umm.echo10.echo10-core :as echo10]))

(use-fixtures :each (join-fixtures
                     [(ingest/reset-fixture {"provguid1" "PROV1"}
                                            {:grant-all-ingest? true
                                             :grant-all-search? true
                                             :grant-all-access-control? false})]))

(deftest invalid-provider-bulk-index-validation-test
  (s/only-with-real-database
    (testing "Validation of a provider supplied in a bulk-index request."
      (let [{:keys [status errors]} (bootstrap/bulk-index-provider "NCD4580")]
        (is (= [400 ["Provider: [NCD4580] does not exist in the system"]]
               [status errors]))))))

(deftest collection-bulk-index-validation-test
  (s/only-with-real-database
    (let [umm1 (dc/collection {:short-name "coll1" :entry-title "coll1"})
          xml1 (echo10/umm->echo10-xml umm1)
          coll1 (mdb/save-concept {:concept-type :collection
                                   :format "application/echo10+xml"
                                   :metadata xml1
                                   :extra-fields {:short-name "coll1"
                                                  :entry-title "coll1"
                                                  :entry-id "coll1"
                                                  :version-id "v1"}
                                   :provider-id "PROV1"
                                   :native-id "coll1"
                                   :short-name "coll1"})
          umm1 (merge umm1 (select-keys coll1 [:concept-id :revision-id]))
          ummg1 (dg/granule coll1 {:granule-ur "gran1"})
          xmlg1 (echo10/umm->echo10-xml ummg1)
          gran1 (mdb/save-concept {:concept-type :granule
                                   :provider-id "PROV1"
                                   :native-id "gran1"
                                   :format "application/echo10+xml"
                                   :metadata xmlg1
                                   :extra-fields {:parent-collection-id (:concept-id umm1)
                                                  :parent-entry-title "coll1"}})
          valid-prov-id "PROV1"
          valid-coll-id (:concept-id umm1)
          invalid-prov-id "NCD4580"
          invalid-coll-id "C12-PROV1"
          err-msg1 (format "Provider: [%s] does not exist in the system" invalid-prov-id)
          err-msg2 (format "Collection [%s] does not exist." invalid-coll-id)
          {:keys [status errors] :as succ-stat} (bootstrap/bulk-index-collection
                                                  valid-prov-id valid-coll-id)
          ;; invalid provider and collection
          {:keys [status errors] :as fail-stat1} (bootstrap/bulk-index-collection
                                                   invalid-prov-id invalid-coll-id)
          ;; valid provider and invalid collection
          {:keys [status errors] :as fail-stat2} (bootstrap/bulk-index-collection
                                                   valid-prov-id invalid-coll-id)
          ;; invalid provider and valid collection
          {:keys [status errors] :as fail-stat3} (bootstrap/bulk-index-collection
                                                   invalid-prov-id valid-coll-id)]

      (testing "Validation of a collection supplied in a bulk-index request."
        (are [expected actual] (= expected actual)
             [202 nil] [(:status succ-stat) (:errors succ-stat)]
             [400 [err-msg1]] [(:status fail-stat1) (:errors fail-stat1)]
             [400 [err-msg2]] [(:status fail-stat2) (:errors fail-stat2)]
             [400 [err-msg1]] [(:status fail-stat3) (:errors fail-stat3)])))))
