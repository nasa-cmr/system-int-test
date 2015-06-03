(ns ^{:doc "provides index related utilities."}
  cmr.system-int-test.utils.index-util
  (:require [clojure.test :refer [is]]
            [clj-http.client :as client]
            [cmr.system-int-test.utils.url-helper :as url]
            [cmr.indexer.config :as config]
            [cmr.system-int-test.utils.queue :as queue]
            [cmr.transmit.config :as transmit-config]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cheshire.core :as json]
            [cmr.system-int-test.system :as s]))

(defn refresh-elastic-index
  []
  (client/post (url/elastic-refresh-url) {:connection-manager (s/conn-mgr)}))

(defn wait-until-indexed
  "Wait until ingested concepts have been indexed"
  []
  (client/post (url/dev-system-wait-for-indexing-url) {:connection-manager (s/conn-mgr)})
  (refresh-elastic-index))

(defn update-indexes
  "Makes the indexer update the index set mappings and indexes"
  []
  (let [response (client/post (url/indexer-update-indexes)
                   {:connection-manager (s/conn-mgr)
                    :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                    :throw-exceptions false})]
    (is (= 200 (:status response)) (:body response))))

(defn set-message-queue-retry-behavior
  "Set the message queue retry behavior"
  [num-retries]
  (client/post
    (url/dev-system-set-message-queue-retry-behavior-url)
    {:connection-manager (s/conn-mgr)
     :query-params {:num-retries num-retries}}))

(defn set-message-queue-publish-timeout
  "Set the message queue publish timeout"
  [timeout]
  (client/post
    (url/dev-system-set-message-queue-publish-timeout-url)
    {:connection-manager (s/conn-mgr)
     :query-params {:timeout timeout}}))

(defn get-message-queue-history
  "Returns the message queue history."
  []
  (-> (client/get (url/dev-system-get-message-queue-history-url) {:connection-manager (s/conn-mgr)})
      :body
      (json/decode true)))

(defn- messages+id->message
  "Returns the first message for a given message id."
  [messages id]
  (first (filter #(= id (:id %)) messages)))

(defn- concept-history
  "Returns a map of concept id revision id tuples to the sequence of states for each one."
  [message-states]
  (let [int-states (for [mq message-states
                         :let [{{:keys [action-type]
                                 {:keys [concept-id revision-id id]} :message} :action} mq
                               result-state (:state (messages+id->message (:messages mq) id))]]
                     {[concept-id revision-id] [{:action action-type :result result-state}]})]
    (apply merge-with concat int-states)))

(defn get-concept-message-queue-history
  "Gets the message queue history and then returns a map of concept-id revision-id tuples to the
  sequence of states for each one."
  []
  (concept-history (get-message-queue-history)))

(defn reset-message-queue-behavior-fixture
  "This is a clojure.test fixture that will reset the message queue behavior to normal processing
  after a test completes."
  []
  (fn [f]
    (try
      (f)
      (finally
        (s/only-with-real-message-queue
          (set-message-queue-retry-behavior 0)
          (set-message-queue-publish-timeout 10000))))))
