(ns openadr3.reports-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1 ven2 bl bad-token inter-suite-delay-ms]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Shared state — event created once in fixture, reused by all tests
;; ---------------------------------------------------------------------------

(def ^:private test-state (atom {}))

(defn- pid [] (:program-id @test-state))
(defn- eid [] (:event-id @test-state))

(defn- report-body
  "Create a basic report request body using the shared event."
  ([] (report-body "TestReport"))
  ([report-name]
   {:programID (pid)
    :eventID (eid)
    :clientName "test-client"
    :reportName report-name
    :resources [{:resourceName "resource-1"
                 :intervals [{:id 0
                              :payloads [{:type "USAGE" :values [100]}]}]}]}))

(defn- delete-all-reports []
  (let [reports (-> (client/get-reports bl) :body)]
    (doseq [{id :id} reports]
      (client/delete-report ven1 id)
      (client/delete-report ven2 id))))

;; ---------------------------------------------------------------------------
;; Fixture: create shared event, clean up after
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (delete-all-reports)
    (let [program-id (:id (client/find-program-by-name bl "Program1"))
          event-resp (client/create-event bl {:programID program-id
                                              :intervals [{:id 0
                                                           :payloads [{:type "PRICE"
                                                                       :values [1.0]}]}]})
          event-id   (-> event-resp :body :id)]
      (reset! test-state {:program-id program-id :event-id event-id})
      (try
        (f)
        (finally
          (delete-all-reports)
          (when event-id (client/delete-event bl event-id)))))))

;; ---------------------------------------------------------------------------
;; Report creation — VEN only
;; ---------------------------------------------------------------------------

(deftest test-create-report-ven
  (testing "VEN can create a report"
    (let [resp (client/create-report ven1 (report-body))]
      (is (= 201 (:status resp)) "VEN should create a report (201)")
      (is (some? (-> resp :body :id)) "Response should include report ID"))))

(deftest test-create-report-bl-forbidden
  (testing "BL cannot create a report"
    (let [resp (client/create-report bl (report-body))]
      (is (= 403 (:status resp)) "BL should be forbidden from creating reports"))))

;; ---------------------------------------------------------------------------
;; Search reports
;; ---------------------------------------------------------------------------

(deftest test-search-all-reports-bl
  (testing "BL can search all reports"
    (client/create-report ven1 (report-body "SearchTest"))
    (let [resp (client/get-reports bl)]
      (is (= 200 (:status resp)) "BL search should succeed")
      (is (>= (count (:body resp)) 1) "Should find at least one report"))))

(deftest test-search-all-reports-ven
  (testing "VEN can search reports (sees own)"
    (let [resp (client/get-reports ven1)]
      (is (= 200 (:status resp)) "VEN search should succeed"))))

(deftest test-search-report-by-id-bl
  (testing "BL can get a report by ID"
    (let [created   (client/create-report ven1 (report-body "ByIdBL"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/get-report-by-id bl report-id)]
          (is (= 200 (:status resp)) "Get by ID should succeed"))))))

(deftest test-search-report-by-id-ven
  (testing "VEN can get own report by ID"
    (let [created   (client/create-report ven1 (report-body "ByIdVEN"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/get-report-by-id ven1 report-id)]
          (is (= 200 (:status resp)) "VEN should get own report"))))))

;; ---------------------------------------------------------------------------
;; Update reports — VEN only
;; ---------------------------------------------------------------------------

(deftest test-update-report-ven
  (testing "VEN can update own report"
    (let [created   (client/create-report ven1 (report-body "UpdateMe"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/update-report ven1 report-id
                                         {:programID (pid)
                                          :eventID (eid)
                                          :clientName "updated-client"
                                          :reportName "UpdatedReport"
                                          :resources [{:resourceName "resource-1"
                                                       :intervals [{:id 0
                                                                    :payloads [{:type "USAGE"
                                                                                :values [200]}]}]}]})]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "UpdatedReport" (-> resp :body :reportName))
              "Report name should be updated"))))))

(deftest test-update-report-bl-forbidden
  (testing "BL cannot update a report"
    (let [created   (client/create-report ven1 (report-body "NoUpdateBL"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/update-report bl report-id
                                         {:programID (pid) :eventID (eid)
                                          :clientName "hack" :reportName "Hacked"
                                          :resources []})]
          (is (= 403 (:status resp)) "BL should be forbidden from updating reports"))))))

;; ---------------------------------------------------------------------------
;; Delete reports — VEN only
;; ---------------------------------------------------------------------------

(deftest test-delete-report-ven
  (testing "VEN can delete own report"
    (let [created   (client/create-report ven1 (report-body "DeleteMe"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/delete-report ven1 report-id)]
          (is (= 200 (:status resp)) "VEN should delete own report"))))))

(deftest test-delete-report-bl-forbidden
  (testing "BL cannot delete a report"
    (let [created   (client/create-report ven1 (report-body "NoDeleteBL"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/delete-report bl report-id)]
          (is (= 403 (:status resp)) "BL should be forbidden from deleting reports"))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest test-create-report-bad-token
  (testing "Bad token cannot create a report"
    (let [resp (client/create-report bad-token (report-body))]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-reports-bad-token
  (testing "Bad token cannot search reports"
    (let [resp (client/get-reports bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-report-by-id-bad-token
  (testing "Bad token cannot get a report by ID"
    (let [created   (client/create-report ven1 (report-body "BadTokenById"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/get-report-by-id bad-token report-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-update-report-bad-token
  (testing "Bad token cannot update a report"
    (let [created   (client/create-report ven1 (report-body "BadTokenUpdate"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/update-report bad-token report-id
                                         {:programID (pid) :eventID (eid)
                                          :clientName "x" :reportName "x"
                                          :resources []})]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-delete-report-bad-token
  (testing "Bad token cannot delete a report"
    (let [created   (client/create-report ven1 (report-body "BadTokenDelete"))
          report-id (-> created :body :id)]
      (is (some? report-id) "Need a report ID")
      (when report-id
        (let [resp (client/delete-report bad-token report-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

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
    (let [resp (client/update-report ven1 "nonexistent-id-12345"
                                     {:programID (pid) :eventID "fake"
                                      :clientName "x" :reportName "x"
                                      :resources []})]
      (is (#{400 404} (:status resp))
          "Should return 404 NOT_FOUND or 400 BAD_REQUEST"))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-reports-pagination
  (testing "Pagination with skip and limit"
    (let [r1 (client/create-report ven1 (report-body "PagReport1"))
          r2 (client/create-report ven1 (report-body "PagReport2"))
          r3 (client/create-report ven1 (report-body "PagReport3"))
          ids (mapv #(-> % :body :id) [r1 r2 r3])]
      (is (= 201 (:status r1)))
      (is (= 201 (:status r2)))
      (is (= 201 (:status r3)))

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-reports bl {:limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all  (-> (client/get-reports bl) :body count)
              resp (client/search-reports bl {:skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-reports bl {:skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-reports bl {:skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp))))))

      ;; Clean up
      (doseq [id ids]
        (when id (client/delete-report ven1 id))))))
