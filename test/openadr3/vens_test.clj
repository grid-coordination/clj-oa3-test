(ns openadr3.vens-test
  (:require [openadr3.client.base :as client]
            [openadr3.client.ven :as ven]
            [openadr3.common-test :refer [ven1 ven2 bl bad-token
                                          ven-route-enabled?]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private ven-vens? (ven-route-enabled? :vens))

;; ---------------------------------------------------------------------------
;; Test data
;; ---------------------------------------------------------------------------

(def cleanup-ven-names ["ven1" "ven2" "BLCreatedVEN" "VENCreatedVEN"
                        "ConflictVEN" "ConflictVEN2"
                        "UpdateVEN" "DeleteVEN"
                        "BadTokenVEN" "PaginationA" "PaginationB" "PaginationC"])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn delete-ven-by-name [c ven-name]
  (let [{ven-id :id} (client/find-ven-by-name c ven-name)]
    (when ven-id
      (client/delete-ven c ven-id))))

(defn delete-test-vens [c]
  (doseq [ven-name cleanup-ven-names]
    (delete-ven-by-name c ven-name)))

;; ---------------------------------------------------------------------------
;; Fixture: clean up, then register foundational VENs
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (delete-test-vens bl)
    ;; Register ven1 and ven2 in the fixture so all tests can use ven-id
    ;; regardless of kaocha test ordering. Skip when the VEN port doesn't
    ;; expose /vens — registration would 404 and the suite asserts that
    ;; case below instead of registering.
    (when ven-vens?
      (ven/register! ven1 "ven1")
      (ven/register! ven2 "ven2"))
    (f)))

;; ---------------------------------------------------------------------------
;; VEN registration
;; ---------------------------------------------------------------------------

(deftest test-register-ven1
  (if ven-vens?
    (testing "ven1 is registered (done in fixture)"
      (is (some? (ven/ven-id ven1)) "ven1 should have a ven-id"))
    (testing "ven1 registration skipped (VEN /vens route disabled)"
      (is (nil? (ven/ven-id ven1)) "ven1 should not have a ven-id"))))

(deftest test-register-ven2
  (if ven-vens?
    (testing "ven2 is registered (done in fixture)"
      (is (some? (ven/ven-id ven2)) "ven2 should have a ven-id"))
    (testing "ven2 registration skipped (VEN /vens route disabled)"
      (is (nil? (ven/ven-id ven2)) "ven2 should not have a ven-id"))))

;; ---------------------------------------------------------------------------
;; VEN creation: BL and VEN roles
;; ---------------------------------------------------------------------------

(deftest test-create-ven-bl
  (testing "BL can create a VEN"
    (let [resp (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                      :venName "BLCreatedVEN"
                                      :clientID "bl_test_client"})]
      (is (= 201 (:status resp)) "BL should create a VEN (201)")
      (is (some? (-> resp :body :id)) "Response should include VEN ID"))))

(deftest test-create-ven-ven
  (if ven-vens?
    (testing "VEN can create a VEN"
      ;; Note: VEN token ties to a clientID. Since ven1 is already registered,
      ;; creating another VEN with ven1's token conflicts (409). This is expected
      ;; VTN behavior — each VEN token can only have one VEN registration.
      ;; We verify the VTN correctly rejects with 409.
      (let [resp (client/create-ven ven1 {:objectType "VEN_VEN_REQUEST"
                                          :venName "VENCreatedVEN"})]
        (is (= 409 (:status resp))
            "VEN token already registered, should conflict on clientID")))
    (testing "VEN cannot create VENs (route disabled)"
      (let [resp (client/create-ven ven1 {:objectType "VEN_VEN_REQUEST"
                                          :venName "VENCreatedVEN"})]
        (is (= 404 (:status resp)) "VEN /vens route should return 404")))))

;; ---------------------------------------------------------------------------
;; Conflict detection (clientID uniqueness — VTN-RI does not enforce venName)
;; ---------------------------------------------------------------------------

(deftest test-create-ven-conflict-client-id
  (testing "Duplicate clientID should conflict"
    (let [resp1 (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                       :venName "ConflictVEN"
                                       :clientID "conflict_client_1"})]
      (is (= 201 (:status resp1)) "First creation should succeed")
      (let [resp2 (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                         :venName "ConflictVEN2"
                                         :clientID "conflict_client_1"})]
        (is (= 409 (:status resp2)) "Duplicate clientID should return 409 CONFLICT")))))

;; ---------------------------------------------------------------------------
;; Search VENs
;; ---------------------------------------------------------------------------

(deftest test-search-all-vens-bl
  (testing "BL can search all VENs"
    (let [resp (client/get-vens bl)]
      (is (= 200 (:status resp)) "Search should succeed")
      (is (>= (count (:body resp)) 2) "Should find at least ven1 and ven2"))))

(deftest test-search-all-vens-ven
  (if ven-vens?
    (testing "VEN can search VENs (sees own VENs)"
      (let [resp (client/get-vens ven1)]
        (is (= 200 (:status resp)) "VEN search should succeed")
        (is (pos? (count (:body resp))) "VEN should see at least one VEN")))
    (testing "VEN cannot search VENs (route disabled)"
      (let [resp (client/get-vens ven1)]
        (is (= 404 (:status resp)) "VEN /vens route should return 404")))))

(deftest test-search-ven-by-id-bl
  (testing "BL can get a VEN by ID"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 should be registered")
      (when ven-id
        (let [resp (client/get-ven-by-id bl ven-id)]
          (is (= 200 (:status resp)) "Get by ID should succeed")
          (is (= "ven1" (-> resp :body :venName)) "Should return correct VEN"))))))

(deftest test-search-ven-by-id-ven
  (if ven-vens?
    (testing "VEN can get own VEN by ID"
      (let [ven-id (ven/ven-id ven1)]
        (is (some? ven-id) "ven1 should be registered")
        (when ven-id
          (let [resp (client/get-ven-by-id ven1 ven-id)]
            (is (= 200 (:status resp)) "VEN should get own VEN by ID")))))
    (testing "VEN cannot get VEN by ID (route disabled)"
      (let [resp (client/get-ven-by-id ven1 "any-id")]
        (is (= 404 (:status resp)) "VEN /vens route should return 404")))))

(deftest ^:auth test-search-ven-by-id-ven-cross
  (testing "VEN cannot get another VEN by ID (cross-VEN scope)"
    (let [ven2-id (ven/ven-id ven2)]
      (is (some? ven2-id) "ven2 should be registered")
      (when ven2-id
        (let [resp (client/get-ven-by-id ven1 ven2-id)]
          (is (= 403 (:status resp))
              "ven1 querying ven2's record should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Update VENs
;; ---------------------------------------------------------------------------

(deftest test-update-ven-bl
  (testing "BL can update a VEN"
    (let [create-resp (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                             :venName "UpdateVEN"
                                             :clientID "update_client"})
          ven-id (-> create-resp :body :id)]
      (is (some? ven-id) "Need a VEN ID")
      (when ven-id
        (let [resp (client/update-ven bl ven-id
                                      {:objectType "BL_VEN_REQUEST"
                                       :venName "UpdateVEN"
                                       :clientID "update_client"
                                       :attributes [{:type "SOME_ATTRIBUTE" :values ["val1"]}]})]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "SOME_ATTRIBUTE" (-> resp :body :attributes first :type))
              "Attribute should be set"))))))

(deftest test-update-ven-ven
  (if ven-vens?
    (testing "VEN can update own VEN"
      (let [ven-id (ven/ven-id ven1)]
        (is (some? ven-id) "ven1 should be registered")
        (when ven-id
          (let [resp (client/update-ven ven1 ven-id {:objectType "VEN_VEN_REQUEST"
                                                     :venName "ven1"})]
            (is (= 200 (:status resp)) "VEN should update own VEN")))))
    (testing "VEN cannot update VENs (route disabled)"
      (let [resp (client/update-ven ven1 "any-id"
                                    {:objectType "VEN_VEN_REQUEST" :venName "x"})]
        (is (= 404 (:status resp)) "VEN /vens route should return 404")))))

(deftest ^:auth test-update-ven-ven-cross
  (testing "VEN cannot update another VEN (cross-VEN scope)"
    (let [ven2-id (ven/ven-id ven2)]
      (is (some? ven2-id) "ven2 should be registered")
      (when ven2-id
        (let [resp (client/update-ven ven1 ven2-id
                                      {:objectType "VEN_VEN_REQUEST"
                                       :venName "ven2-tampered"})]
          (is (= 403 (:status resp))
              "ven1 updating ven2's record should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Delete VENs
;; ---------------------------------------------------------------------------

(deftest test-delete-ven-bl
  (testing "BL can delete a VEN"
    (let [create-resp (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                             :venName "DeleteVEN"
                                             :clientID "delete_client"})
          ven-id (-> create-resp :body :id)]
      (is (some? ven-id) "Need a VEN ID")
      (when ven-id
        (let [resp (client/delete-ven bl ven-id)]
          (is (= 200 (:status resp)) "BL should delete a VEN"))))))

(deftest ^:auth test-delete-ven-ven-cross
  (testing "VEN cannot delete another VEN (cross-VEN scope)"
    (let [ven2-id (ven/ven-id ven2)]
      (is (some? ven2-id) "ven2 should be registered")
      (when ven2-id
        (let [resp (client/delete-ven ven1 ven2-id)]
          (is (= 403 (:status resp))
              "ven1 deleting ven2's record should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest ^:auth test-create-ven-bad-token
  (testing "Bad token cannot create a VEN"
    (let [resp (client/create-ven bad-token {:objectType "BL_VEN_REQUEST"
                                             :venName "BadTokenVEN"
                                             :clientID "bad_client"})]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest ^:auth test-search-vens-bad-token
  (testing "Bad token cannot search VENs"
    (let [resp (client/get-vens bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest ^:auth test-search-ven-by-id-bad-token
  (testing "Bad token cannot get a VEN by ID"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 should be registered")
      (when ven-id
        (let [resp (client/get-ven-by-id bad-token ven-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest ^:auth test-update-ven-bad-token
  (testing "Bad token cannot update a VEN"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 should be registered")
      (when ven-id
        (let [resp (client/update-ven bad-token ven-id {:objectType "VEN_VEN_REQUEST"
                                                        :venName "ven1"})]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest ^:auth test-delete-ven-bad-token
  (testing "Bad token cannot delete a VEN"
    (let [ven-id (ven/ven-id ven1)]
      (is (some? ven-id) "ven1 should be registered")
      (when ven-id
        (let [resp (client/delete-ven bad-token ven-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-ven-bad-id
  (testing "Non-existent VEN ID returns 404"
    (let [resp (client/get-ven-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-ven-bad-id
  (testing "Delete non-existent VEN returns 404"
    (let [resp (client/delete-ven bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-ven-bad-id
  (testing "Update non-existent VEN returns 404 or 400"
    (let [resp (client/update-ven bl "nonexistent-id-12345"
                                  {:objectType "BL_VEN_REQUEST"
                                   :venName "Ghost" :clientID "ghost_client"})]
      (is (#{400 404} (:status resp))
          "Should return 404 NOT_FOUND or 400 BAD_REQUEST"))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-vens-pagination
  (testing "Pagination with skip and limit"
    ;; Create 3 VENs for pagination testing
    (let [r1 (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                    :venName "PaginationA" :clientID "pag_a"})
          r2 (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                    :venName "PaginationB" :clientID "pag_b"})
          r3 (client/create-ven bl {:objectType "BL_VEN_REQUEST"
                                    :venName "PaginationC" :clientID "pag_c"})]
      (is (= 201 (:status r1)))
      (is (= 201 (:status r2)))
      (is (= 201 (:status r3)))

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-vens bl {:limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all   (-> (client/get-vens bl) :body count)
              resp  (client/search-vens bl {:skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-vens bl {:skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-vens bl {:skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp))))))

      (testing "search by venName"
        (let [resp (client/search-vens bl {:venName "PaginationA"})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp)))
              "Should find exactly one VEN with that name"))))))
