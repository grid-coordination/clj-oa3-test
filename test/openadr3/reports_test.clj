(ns openadr3.reports-test
  (:require [openadr3.client :as client]
            [openadr3.common-test :refer [ven1 ven2 bl bad-token]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- find-program-id []
  (:id (client/find-program-by-name bl "Program1")))

(defn- report-body
  "Create a basic report request body. Requires a programID and eventID."
  [program-id event-id]
  {:programID program-id
   :eventID event-id
   :clientName "test-client"
   :reportName "TestReport"
   :resources [{:resourceName "resource-1"
                :intervals [{:id 0
                             :payloads [{:type "USAGE" :values [100]}]}]}]})

(defn- create-test-event
  "Create a temporary event and return its ID."
  [program-id]
  (let [resp (client/create-event bl {:programID program-id
                                      :intervals [{:id 0
                                                   :payloads [{:type "PRICE"
                                                               :values [1.0]}]}]})]
    (-> resp :body :id)))

(defn- delete-all-reports []
  (let [reports (-> (client/get-reports bl) :body)]
    (doseq [{id :id} reports]
      ;; Reports can only be deleted by VEN — try both
      (client/delete-report ven1 id)
      (client/delete-report ven2 id))))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (delete-all-reports)
    (f)))

;; ---------------------------------------------------------------------------
;; Report creation — VEN only
;; ---------------------------------------------------------------------------

(deftest test-create-report-ven
  (testing "VEN can create a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/create-report ven1 (report-body pid event-id))]
          (is (= 201 (:status resp)) "VEN should create a report (201)")
          (is (some? (-> resp :body :id)) "Response should include report ID"))
        (client/delete-event bl event-id)))))

(deftest test-create-report-bl-forbidden
  (testing "BL cannot create a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (is (some? event-id) "Need an event ID")
      (when event-id
        (let [resp (client/create-report bl (report-body pid event-id))]
          (is (= 403 (:status resp)) "BL should be forbidden from creating reports"))
        (client/delete-event bl event-id)))))

;; ---------------------------------------------------------------------------
;; Search reports
;; ---------------------------------------------------------------------------

(deftest test-search-all-reports-bl
  (testing "BL can search all reports"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (client/create-report ven1 (report-body pid event-id))
        (let [resp (client/get-reports bl)]
          (is (= 200 (:status resp)) "BL search should succeed")
          (is (>= (count (:body resp)) 1) "Should find at least one report"))
        (client/delete-event bl event-id)))))

(deftest test-search-all-reports-ven
  (testing "VEN can search reports (sees own)"
    (let [resp (client/get-reports ven1)]
      (is (= 200 (:status resp)) "VEN search should succeed"))))

(deftest test-search-report-by-id-bl
  (testing "BL can get a report by ID"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/get-report-by-id bl report-id)]
              (is (= 200 (:status resp)) "Get by ID should succeed")))
          (client/delete-event bl event-id))))))

(deftest test-search-report-by-id-ven
  (testing "VEN can get own report by ID"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/get-report-by-id ven1 report-id)]
              (is (= 200 (:status resp)) "VEN should get own report")))
          (client/delete-event bl event-id))))))

;; ---------------------------------------------------------------------------
;; Update reports — VEN only
;; ---------------------------------------------------------------------------

(deftest test-update-report-ven
  (testing "VEN can update own report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/update-report ven1 report-id
                                             {:programID pid
                                              :eventID event-id
                                              :clientName "updated-client"
                                              :reportName "UpdatedReport"
                                              :resources [{:resourceName "resource-1"
                                                           :intervals [{:id 0
                                                                        :payloads [{:type "USAGE"
                                                                                    :values [200]}]}]}]})]
              (is (= 200 (:status resp)) "Update should succeed")
              (is (= "UpdatedReport" (-> resp :body :reportName))
                  "Report name should be updated")))
          (client/delete-event bl event-id))))))

(deftest test-update-report-bl-forbidden
  (testing "BL cannot update a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/update-report bl report-id
                                             {:programID pid
                                              :eventID event-id
                                              :clientName "hack"
                                              :reportName "Hacked"
                                              :resources []})]
              (is (= 403 (:status resp)) "BL should be forbidden from updating reports")))
          (client/delete-event bl event-id))))))

;; ---------------------------------------------------------------------------
;; Delete reports — VEN only
;; ---------------------------------------------------------------------------

(deftest test-delete-report-ven
  (testing "VEN can delete own report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/delete-report ven1 report-id)]
              (is (= 200 (:status resp)) "VEN should delete own report"))))
        (client/delete-event bl event-id)))))

(deftest test-delete-report-bl-forbidden
  (testing "BL cannot delete a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/delete-report bl report-id)]
              (is (= 403 (:status resp)) "BL should be forbidden from deleting reports")))
          (client/delete-event bl event-id))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest test-create-report-bad-token
  (testing "Bad token cannot create a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [resp (client/create-report bad-token (report-body pid event-id))]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-event bl event-id)))))

(deftest test-search-reports-bad-token
  (testing "Bad token cannot search reports"
    (let [resp (client/get-reports bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-report-by-id-bad-token
  (testing "Bad token cannot get a report by ID"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/get-report-by-id bad-token report-id)]
              (is (= 403 (:status resp)) "Bad token should be forbidden")))
          (client/delete-event bl event-id))))))

(deftest test-update-report-bad-token
  (testing "Bad token cannot update a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/update-report bad-token report-id
                                             {:programID pid :eventID event-id
                                              :clientName "x" :reportName "x"
                                              :resources []})]
              (is (= 403 (:status resp)) "Bad token should be forbidden")))
          (client/delete-event bl event-id))))))

(deftest test-delete-report-bad-token
  (testing "Bad token cannot delete a report"
    (let [pid      (find-program-id)
          event-id (create-test-event pid)]
      (when event-id
        (let [created   (client/create-report ven1 (report-body pid event-id))
              report-id (-> created :body :id)]
          (when report-id
            (let [resp (client/delete-report bad-token report-id)]
              (is (= 403 (:status resp)) "Bad token should be forbidden")))
          (client/delete-event bl event-id))))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-report-bad-id
  (testing "Non-existent report ID returns 404"
    (let [resp (client/get-report-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-report-bad-id
  (testing "Delete non-existent report returns 404"
    (let [resp (client/delete-report ven1 "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-report-bad-id
  (testing "Update non-existent report returns 404 or 400"
    (let [pid (find-program-id)
          resp (client/update-report ven1 "nonexistent-id-12345"
                                     {:programID pid :eventID "fake"
                                      :clientName "x" :reportName "x"
                                      :resources []})]
      (is (#{400 404} (:status resp))
          "Should return 404 NOT_FOUND or 400 BAD_REQUEST"))))
