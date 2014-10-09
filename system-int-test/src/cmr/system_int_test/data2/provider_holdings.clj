(ns cmr.system-int-test.data2.provider-holdings
  "Contains helper functions for converting provider holdings into the expected map of parsed results."
  (:require [clojure.data.xml :as x]
            [cmr.common.xml :as cx]
            [cheshire.core :as json]
            [clojure.set :as set]))

(defmulti parse-provider-holdings
  "Returns the parsed provider holdings based on the given format and result string"
  (fn [format echo-compatible? result]
    format))

(defmulti xml-elem->provider-holding
  "Returns the provider holding entry by parsing the given xml struct"
  (fn [echo-compatible? xml-elem]
    echo-compatible?))

(defmethod xml-elem->provider-holding false
  [echo-compatible? xml-elem]
  {:entry-title (cx/string-at-path xml-elem [:entry-title])
   :concept-id (cx/string-at-path xml-elem [:concept-id])
   :granule-count (cx/long-at-path xml-elem [:granule-count])
   :provider-id (cx/string-at-path xml-elem [:provider-id])})

(defmethod xml-elem->provider-holding true
  [echo-compatible? xml-elem]
  {:entry-title (cx/string-at-path xml-elem [:dataset_id])
   :concept-id (cx/string-at-path xml-elem [:echo_collection_id])
   :granule-count (cx/long-at-path xml-elem [:granule_count])
   :provider-id (cx/string-at-path xml-elem [:provider_id])})

(defmethod parse-provider-holdings :xml
  [format echo-compatible? xml]
  (let [xml-struct (x/parse-str xml)]
    (map (partial xml-elem->provider-holding echo-compatible?)
         (cx/elements-at-path xml-struct [:provider-holding]))))

(defn- echo-provider-holding->cmr-provider-holding
  "Returns the provider holding in CMR format for the given provider holding in ECHO format"
  [echo-holding]
  (set/rename-keys echo-holding {:dataset_id :entry-title
                                 :echo_collection_id :concept-id
                                 :granule_count :granule-count
                                 :provider_id :provider-id}))

(defmethod parse-provider-holdings :json
  [format echo-compatible? json-str]
  (let [parsed-json (json/decode json-str true)]
    (if echo-compatible?
      (map echo-provider-holding->cmr-provider-holding parsed-json)
      parsed-json)))

