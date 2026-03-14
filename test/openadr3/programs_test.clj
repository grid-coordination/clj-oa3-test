(ns openadr3.programs-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1 bl bad-token]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Test data
;; ---------------------------------------------------------------------------

(def program1-request {:programName "Program1"})
(def program2-request {:programName "Program2"})
(def test-program-requests [program1-request program2-request])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn delete-program-by-name [c program-name]
  (let [{program-id :id} (client/find-program-by-name c program-name)]
    (when program-id
      (client/delete-program c program-id))))

(defn delete-test-programs [c]
  (doseq [{program-name :programName} test-program-requests]
    (delete-program-by-name c program-name)))

;; ---------------------------------------------------------------------------
;; Fixture: clean up test programs before running
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (delete-test-programs bl)
    (delete-program-by-name bl "ConflictTest")
    (delete-program-by-name bl "UpdateTest")
    (delete-program-by-name bl "DeleteTest")
    (delete-program-by-name bl "BadTokenTest")
    (delete-program-by-name bl "PaginationA")
    (delete-program-by-name bl "PaginationB")
    (delete-program-by-name bl "PaginationC")
    (f)))

;; ---------------------------------------------------------------------------
;; Program creation — these two create foundational data used by later suites
;; ---------------------------------------------------------------------------

(deftest test-create-program1
  (testing "Create Program1 with BL token"
    (let [resp (client/create-program bl program1-request)]
      (is (= 201 (:status resp)) "BL should create a program (201)"))))

(deftest test-create-program2
  (testing "Create Program2 with BL token"
    (let [resp (client/create-program bl program2-request)]
      (is (= 201 (:status resp)) "BL should create a program (201)"))))

;; ---------------------------------------------------------------------------
;; Authorization: VEN cannot create programs
;; ---------------------------------------------------------------------------

(deftest test-create-program-ven-forbidden
  (testing "VEN cannot create a program"
    (let [resp (client/create-program ven1 {:programName "VENProgram"})]
      (is (= 403 (:status resp)) "VEN should be forbidden from creating programs"))))

;; ---------------------------------------------------------------------------
;; Conflict detection: duplicate programName
;; ---------------------------------------------------------------------------

(deftest test-create-program-conflict
  (testing "Duplicate programName should conflict"
    (let [resp1 (client/create-program bl {:programName "ConflictTest"})]
      (is (= 201 (:status resp1)) "First creation should succeed")
      (let [resp2 (client/create-program bl {:programName "ConflictTest"})]
        (is (= 409 (:status resp2)) "Duplicate programName should return 409 CONFLICT")))))

;; ---------------------------------------------------------------------------
;; Search programs
;; ---------------------------------------------------------------------------

(deftest test-search-all-programs-bl
  (testing "BL can search all programs"
    (let [resp (client/get-programs bl)]
      (is (= 200 (:status resp)) "Search should succeed")
      (is (>= (count (:body resp)) 2) "Should find at least Program1 and Program2"))))

(deftest test-search-all-programs-ven
  (testing "VEN can search programs"
    (let [resp (client/get-programs ven1)]
      (is (= 200 (:status resp)) "VEN should be able to search programs")
      (is (pos? (count (:body resp))) "VEN should see at least one program"))))

(deftest test-search-program-by-id-bl
  (testing "BL can get a program by ID"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/get-program-by-id bl (:id program))]
          (is (= 200 (:status resp)) "Get by ID should succeed")
          (is (= "Program1" (-> resp :body :programName)) "Should return correct program"))))))

(deftest test-search-program-by-id-ven
  (testing "VEN can get a program by ID"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/get-program-by-id ven1 (:id program))]
          (is (= 200 (:status resp)) "VEN should be able to get program by ID"))))))

;; ---------------------------------------------------------------------------
;; Update programs
;; ---------------------------------------------------------------------------

(deftest test-update-program-bl
  (testing "BL can update a program"
    (let [create-resp (client/create-program bl {:programName "UpdateTest"})
          program-id (-> create-resp :body :id)]
      (is (some? program-id) "Need a program ID")
      (when program-id
        (let [resp (client/update-program bl program-id
                                          {:programName "UpdateTest"
                                           :targets ["group1"]})]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "group1" (-> resp :body :targets first))
              "Target should be updated"))))))

(deftest test-update-program-ven-forbidden
  (testing "VEN cannot update a program"
    (let [program (client/find-program-by-name bl "UpdateTest")]
      (is (some? program) "UpdateTest should exist")
      (when program
        (let [resp (client/update-program ven1 (:id program)
                                          {:programName "UpdateTest"})]
          (is (= 403 (:status resp)) "VEN should be forbidden from updating programs"))))))

;; ---------------------------------------------------------------------------
;; Delete programs
;; ---------------------------------------------------------------------------

(deftest test-delete-program-bl
  (testing "BL can delete a program"
    (let [create-resp (client/create-program bl {:programName "DeleteTest"})
          program-id (-> create-resp :body :id)]
      (is (some? program-id) "Need a program ID")
      (when program-id
        (let [resp (client/delete-program bl program-id)]
          (is (= 200 (:status resp)) "Delete should succeed"))))))

(deftest test-delete-program-ven-forbidden
  (testing "VEN cannot delete a program"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/delete-program ven1 (:id program))]
          (is (= 403 (:status resp)) "VEN should be forbidden from deleting programs"))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest test-create-program-bad-token
  (testing "Bad token cannot create a program"
    (let [resp (client/create-program bad-token {:programName "BadTokenTest"})]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-programs-bad-token
  (testing "Bad token cannot search programs"
    (let [resp (client/get-programs bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-program-by-id-bad-token
  (testing "Bad token cannot get a program by ID"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/get-program-by-id bad-token (:id program))]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-update-program-bad-token
  (testing "Bad token cannot update a program"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/update-program bad-token (:id program)
                                          {:programName "Program1"})]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-delete-program-bad-token
  (testing "Bad token cannot delete a program"
    (let [program (client/find-program-by-name bl "Program1")]
      (is (some? program) "Program1 should exist")
      (when program
        (let [resp (client/delete-program bad-token (:id program))]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-program-bad-id
  (testing "Non-existent program ID returns 404"
    (let [resp (client/get-program-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-program-bad-id
  (testing "Delete non-existent program returns 404"
    (let [resp (client/delete-program bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-program-bad-id
  (testing "Update non-existent program returns 404"
    (let [resp (client/update-program bl "nonexistent-id-12345"
                                      {:programName "Ghost"})]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-programs-pagination
  (testing "Pagination with skip and limit"
    ;; Create 3 programs for pagination testing
    (let [r1 (client/create-program bl {:programName "PaginationA"})
          r2 (client/create-program bl {:programName "PaginationB"})
          r3 (client/create-program bl {:programName "PaginationC"})]
      (is (= 201 (:status r1)))
      (is (= 201 (:status r2)))
      (is (= 201 (:status r3)))

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-programs bl {:limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all   (-> (client/get-programs bl) :body count)
              resp  (client/search-programs bl {:skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-programs bl {:skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-programs bl {:skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp)))))))))
