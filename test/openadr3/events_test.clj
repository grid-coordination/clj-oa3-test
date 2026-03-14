(ns openadr3.events-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1 bl bad-token inter-suite-delay-ms]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- find-program-id
  "Get Program1's ID (created by programs-test suite)."
  []
  (:id (client/find-program-by-name bl "Program1")))

(defn- event-body
  "Create a basic event request body for a program."
  [program-id]
  {:programID program-id
   :intervals [{:id 0
                :payloads [{:type "PRICE" :values [1.5]}]}]})

(defn- delete-all-events-for-program
  "Delete all events for a given program."
  [program-id]
  (let [events (-> (client/search-events bl {:programID program-id}) :body)]
    (doseq [{id :id} events]
      (client/delete-event bl id))))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (let [pid (find-program-id)]
      (when pid (delete-all-events-for-program pid)))
    (f)))

;; ---------------------------------------------------------------------------
;; Event creation
;; ---------------------------------------------------------------------------

(deftest test-create-event-bl
  (testing "BL can create an event"
    (let [pid  (find-program-id)
          resp (client/create-event bl (event-body pid))]
      (is (= 201 (:status resp)) "BL should create an event (201)")
      (is (some? (-> resp :body :id)) "Response should include event ID")
      ;; Clean up
      (when-let [id (-> resp :body :id)]
        (client/delete-event bl id)))))

(deftest test-create-event-ven-forbidden
  (testing "VEN cannot create an event"
    (let [pid  (find-program-id)
          resp (client/create-event ven1 (event-body pid))]
      (is (= 403 (:status resp)) "VEN should be forbidden from creating events"))))

(deftest test-create-event-bad-program-id
  (testing "Event creation with invalid programID returns 400"
    (let [resp (client/create-event bl (event-body "nonexistent-program-id"))]
      (is (= 400 (:status resp)) "Invalid programID should return 400"))))

;; ---------------------------------------------------------------------------
;; Search events
;; ---------------------------------------------------------------------------

(deftest test-search-all-events-bl
  (testing "BL can search all events"
    (let [pid  (find-program-id)
          _    (client/create-event bl (event-body pid))
          resp (client/get-events bl)]
      (is (= 200 (:status resp)) "Search should succeed")
      (is (>= (count (:body resp)) 1) "Should find at least one event"))))

(deftest test-search-all-events-ven
  (testing "VEN can search events"
    (let [resp (client/get-events ven1)]
      (is (= 200 (:status resp)) "VEN search should succeed"))))

(deftest test-search-event-by-id-bl
  (testing "BL can get an event by ID"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/get-event-by-id bl event-id)]
          (is (= 200 (:status resp)) "Get by ID should succeed")
          (is (= event-id (-> resp :body :id)) "Should return correct event"))
        (client/delete-event bl event-id)))))

(deftest test-search-event-by-id-ven
  (testing "VEN can get an event by ID"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/get-event-by-id ven1 event-id)]
          (is (= 200 (:status resp)) "VEN should get event by ID"))
        (client/delete-event bl event-id)))))

(deftest test-search-events-by-program-id
  (testing "Search events filtered by programID"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (when event-id
        (let [resp (client/search-events bl {:programID pid})]
          (is (= 200 (:status resp)))
          (is (>= (count (:body resp)) 1) "Should find events for this program"))
        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Update events
;; ---------------------------------------------------------------------------

(deftest test-update-event-bl
  (testing "BL can update an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/update-event bl event-id
                                        {:programID pid
                                         :eventName "updated-event"
                                         :intervals [{:id 0
                                                      :payloads [{:type "PRICE"
                                                                  :values [2.0]}]}]})]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "updated-event" (-> resp :body :eventName))
              "Event name should be updated"))
        (client/delete-event bl event-id)))))

(deftest test-update-event-ven-forbidden
  (testing "VEN cannot update an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/update-event ven1 event-id
                                        {:programID pid
                                         :intervals [{:id 0
                                                      :payloads [{:type "PRICE"
                                                                  :values [1.0]}]}]})]
          (is (= 403 (:status resp)) "VEN should be forbidden from updating events"))
        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Delete events
;; ---------------------------------------------------------------------------

(deftest test-delete-event-bl
  (testing "BL can delete an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/delete-event bl event-id)]
          (is (= 200 (:status resp)) "Delete should succeed"))))))

(deftest test-delete-event-ven-forbidden
  (testing "VEN cannot delete an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/delete-event ven1 event-id)]
          (is (= 403 (:status resp)) "VEN should be forbidden from deleting events"))
        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest test-create-event-bad-token
  (testing "Bad token cannot create an event"
    (let [pid  (find-program-id)
          resp (client/create-event bad-token (event-body pid))]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-events-bad-token
  (testing "Bad token cannot search events"
    (let [resp (client/get-events bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-event-by-id-bad-token
  (testing "Bad token cannot get an event by ID"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/get-event-by-id bad-token event-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-event bl event-id)))))

(deftest test-update-event-bad-token
  (testing "Bad token cannot update an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/update-event bad-token event-id
                                        {:programID pid
                                         :intervals []})]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-event bl event-id)))))

(deftest test-delete-event-bad-token
  (testing "Bad token cannot delete an event"
    (let [pid      (find-program-id)
          created  (client/create-event bl (event-body pid))
          event-id (-> created :body :id)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/delete-event bad-token event-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-event-bad-id
  (testing "Non-existent event ID returns 404"
    (let [resp (client/get-event-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-event-bad-id
  (testing "Delete non-existent event returns 404"
    (let [resp (client/delete-event bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-event-bad-id
  (testing "Update non-existent event returns 404"
    (let [pid (find-program-id)]
      (let [resp (client/update-event bl "nonexistent-id-12345"
                                      {:programID pid :intervals []})]
        (is (= 404 (:status resp)) "Should return 404 NOT_FOUND")))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-events-pagination
  (testing "Pagination with skip and limit"
    (let [pid (find-program-id)
          e1  (client/create-event bl (event-body pid))
          e2  (client/create-event bl (event-body pid))
          e3  (client/create-event bl (event-body pid))
          ids (mapv #(-> % :body :id) [e1 e2 e3])]

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-events bl {:limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all   (-> (client/get-events bl) :body count)
              resp  (client/search-events bl {:skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-events bl {:skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-events bl {:skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp))))))

      ;; Clean up
      (doseq [id ids]
        (when id (client/delete-event bl id))))))
