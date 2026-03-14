(ns openadr3.resources-test
  (:require [openadr3.client.base :as client]
            [openadr3.client.ven :as ven]
            [openadr3.common-test :refer [ven1 ven2 bl bad-token inter-suite-delay-ms]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ven1-id [] (ven/ven-id ven1))

(defn- resource-body
  "Create a VEN resource request body."
  [ven-id resource-name]
  {:venID ven-id
   :objectType "VEN_RESOURCE_REQUEST"
   :resourceName resource-name})

(defn- bl-resource-body
  "Create a BL resource request body."
  [ven-id resource-name client-id]
  {:venID ven-id
   :objectType "BL_RESOURCE_REQUEST"
   :resourceName resource-name
   :clientID client-id})

(defn- delete-all-resources-for-ven
  "Delete all resources for a VEN."
  [ven-id]
  (let [resources (-> (client/search-ven-resources bl {:venID ven-id}) :body)]
    (doseq [{id :id} resources]
      (client/delete-resource bl id))))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (when-let [vid (ven1-id)]
      (delete-all-resources-for-ven vid))
    (f)))

;; ---------------------------------------------------------------------------
;; Resource creation
;; ---------------------------------------------------------------------------

(deftest test-create-resource-ven
  (testing "VEN can create a resource"
    (let [vid  (ven1-id)
          resp (client/create-resource ven1 (resource-body vid "Resource1"))]
      (is (= 201 (:status resp)) "VEN should create a resource (201)")
      (is (some? (-> resp :body :id)) "Response should include resource ID"))))

(deftest test-create-resource-bl
  (testing "BL can create a resource for a VEN"
    (let [vid  (ven1-id)
          resp (client/create-resource bl (bl-resource-body vid "BLResource1" "bl_res_client"))]
      (is (= 201 (:status resp)) "BL should create a resource (201)"))))

(deftest test-create-resource-conflict
  (testing "Duplicate resourceName per VEN should conflict"
    (let [vid   (ven1-id)
          resp1 (client/create-resource ven1 (resource-body vid "ConflictResource"))
          _     (is (= 201 (:status resp1)) "First creation should succeed")
          resp2 (client/create-resource ven1 (resource-body vid "ConflictResource"))]
      (is (= 409 (:status resp2)) "Duplicate resourceName should return 409 CONFLICT"))))

;; ---------------------------------------------------------------------------
;; Search resources
;; ---------------------------------------------------------------------------

(deftest test-search-all-resources-bl
  (testing "BL can search resources for a VEN"
    (let [vid  (ven1-id)
          resp (client/search-ven-resources bl {:venID vid})]
      (is (= 200 (:status resp)) "Search should succeed")
      (is (>= (count (:body resp)) 1) "Should find at least one resource"))))

(deftest test-search-all-resources-ven
  (testing "VEN can search own resources"
    (let [vid  (ven1-id)
          resp (client/search-ven-resources ven1 {:venID vid})]
      (is (= 200 (:status resp)) "VEN search should succeed")
      (is (>= (count (:body resp)) 1) "Should find at least one resource"))))

(deftest test-search-resource-by-id-bl
  (testing "BL can get a resource by ID"
    (let [vid       (ven1-id)
          resources (-> (client/search-ven-resources bl {:venID vid}) :body)
          res-id    (:id (first resources))]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/get-resource-by-id bl res-id)]
          (is (= 200 (:status resp)) "Get by ID should succeed"))))))

(deftest test-search-resource-by-id-ven
  (testing "VEN can get own resource by ID"
    (let [vid       (ven1-id)
          resources (-> (client/search-ven-resources ven1 {:venID vid}) :body)
          res-id    (:id (first resources))]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/get-resource-by-id ven1 res-id)]
          (is (= 200 (:status resp)) "VEN should get own resource"))))))

;; ---------------------------------------------------------------------------
;; Update resources
;; ---------------------------------------------------------------------------

(deftest test-update-resource-bl
  (testing "BL can update a resource"
    (let [vid     (ven1-id)
          created (client/create-resource bl (bl-resource-body vid "UpdateResource" "upd_client"))
          res-id  (-> created :body :id)]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/update-resource bl res-id
                                           {:objectType "BL_RESOURCE_REQUEST"
                                            :venID vid
                                            :clientID "upd_client"
                                            :resourceName "UpdateResource"
                                            :attributes [{:type "SOME_ATTR" :values ["v1"]}]})]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "SOME_ATTR" (-> resp :body :attributes first :type))
              "Attribute should be set"))))))

(deftest test-update-resource-ven
  (testing "VEN can update own resource"
    (let [vid     (ven1-id)
          created (client/create-resource ven1 (resource-body vid "VENUpdateResource"))
          res-id  (-> created :body :id)]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/update-resource ven1 res-id
                                           {:objectType "VEN_RESOURCE_REQUEST"
                                            :venID vid
                                            :resourceName "VENUpdateResource"})]
          (is (= 200 (:status resp)) "VEN should update own resource"))))))

;; ---------------------------------------------------------------------------
;; Delete resources
;; ---------------------------------------------------------------------------

(deftest test-delete-resource-bl
  (testing "BL can delete a resource"
    (let [vid     (ven1-id)
          created (client/create-resource bl (bl-resource-body vid "DeleteResource" "del_client"))
          res-id  (-> created :body :id)]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/delete-resource bl res-id)]
          (is (= 200 (:status resp)) "Delete should succeed"))))))

(deftest test-delete-resource-ven
  (testing "VEN can delete own resource"
    (let [vid     (ven1-id)
          created (client/create-resource ven1 (resource-body vid "VENDeleteResource"))
          res-id  (-> created :body :id)]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/delete-resource ven1 res-id)]
          (is (= 200 (:status resp)) "VEN should delete own resource"))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest test-create-resource-bad-token
  (testing "Bad token cannot create a resource"
    (let [vid  (ven1-id)
          resp (client/create-resource bad-token (resource-body vid "BadTokenResource"))]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-resources-bad-token
  (testing "Bad token cannot search resources"
    (let [vid  (ven1-id)
          resp (client/search-ven-resources bad-token {:venID vid})]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest test-search-resource-by-id-bad-token
  (testing "Bad token cannot get a resource by ID"
    (let [vid       (ven1-id)
          resources (-> (client/search-ven-resources bl {:venID vid}) :body)
          res-id    (:id (first resources))]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/get-resource-by-id bad-token res-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-update-resource-bad-token
  (testing "Bad token cannot update a resource"
    (let [vid       (ven1-id)
          resources (-> (client/search-ven-resources bl {:venID vid}) :body)
          res-id    (:id (first resources))]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/update-resource bad-token res-id {:resourceName "hack"})]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

(deftest test-delete-resource-bad-token
  (testing "Bad token cannot delete a resource"
    (let [vid       (ven1-id)
          resources (-> (client/search-ven-resources bl {:venID vid}) :body)
          res-id    (:id (first resources))]
      (is (some? res-id) "Need a resource ID")
      (when res-id
        (let [resp (client/delete-resource bad-token res-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-resource-bad-id
  (testing "Non-existent resource ID returns 404"
    (let [resp (client/get-resource-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-resource-bad-id
  (testing "Delete non-existent resource returns 404"
    (let [resp (client/delete-resource bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-resource-bad-id
  (testing "Update non-existent resource returns 404 or 400"
    (let [resp (client/update-resource bl "nonexistent-id-12345"
                                       {:resourceName "Ghost"})]
      (is (#{400 404} (:status resp))
          "Should return 404 NOT_FOUND or 400 BAD_REQUEST"))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-resources-pagination
  (testing "Pagination with skip and limit"
    (let [vid (ven1-id)
          r1  (client/create-resource ven1 (resource-body vid "PagRes1"))
          r2  (client/create-resource ven1 (resource-body vid "PagRes2"))
          r3  (client/create-resource ven1 (resource-body vid "PagRes3"))]
      (is (= 201 (:status r1)))
      (is (= 201 (:status r2)))
      (is (= 201 (:status r3)))

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-ven-resources bl {:venID vid :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all   (-> (client/search-ven-resources bl {:venID vid}) :body count)
              resp  (client/search-ven-resources bl {:venID vid :skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-ven-resources bl {:venID vid :skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-ven-resources bl {:venID vid :skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp)))))))))
