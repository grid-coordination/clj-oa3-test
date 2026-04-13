(ns openadr3.subscriptions-test
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [ven1 bl bad-token inter-suite-delay-ms
                                          ven-route-enabled?]]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- find-program-id []
  (:id (client/find-program-by-name bl "Program1")))

(defn- subscription-body
  "Create a basic subscription request body."
  ([] (subscription-body "TestSubscription"))
  ([client-name]
   (subscription-body client-name (find-program-id)))
  ([client-name program-id]
   {:programID program-id
    :clientName client-name
    :objectOperations [{:objects ["EVENT"]
                        :operations ["CREATE"]
                        :callbackUrl "https://example.com/callback"
                        :bearerToken "callback-token"}]}))

(defn- delete-all-subscriptions []
  (let [subs (-> (client/get-subscriptions bl) :body)]
    (doseq [{id :id} subs]
      (client/delete-subscription bl id))))

(def ^:private ven-subscriptions? (ven-route-enabled? :subscriptions))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (delete-all-subscriptions)
    (f)))

;; ---------------------------------------------------------------------------
;; Subscription creation
;; ---------------------------------------------------------------------------

(deftest test-create-subscription-bl
  (testing "BL can create a subscription"
    (let [resp (client/create-subscription bl (subscription-body "BLSub"))]
      (is (= 201 (:status resp)) "BL should create a subscription (201)")
      (is (some? (-> resp :body :id)) "Response should include subscription ID")
      (when-let [id (-> resp :body :id)]
        (client/delete-subscription bl id)))))

(deftest test-create-subscription-ven
  (if ven-subscriptions?
    (testing "VEN can create a subscription"
      (let [resp (client/create-subscription ven1 (subscription-body "VENSub"))]
        (is (= 201 (:status resp)) "VEN should create a subscription (201)")
        (is (some? (-> resp :body :id)) "Response should include subscription ID")
        (when-let [id (-> resp :body :id)]
          (client/delete-subscription bl id))))
    (testing "VEN cannot create subscriptions (route disabled)"
      (let [resp (client/create-subscription ven1 (subscription-body "VENSub"))]
        (is (= 404 (:status resp)) "VEN subscription route should return 404")))))

;; ---------------------------------------------------------------------------
;; Search subscriptions
;; ---------------------------------------------------------------------------

(deftest test-search-all-subscriptions-bl
  (testing "BL can search all subscriptions"
    (let [created (client/create-subscription bl (subscription-body "SearchBL"))
          sub-id  (-> created :body :id)]
      (let [resp (client/get-subscriptions bl)]
        (is (= 200 (:status resp)) "BL search should succeed")
        (is (>= (count (:body resp)) 1) "Should find at least one subscription"))
      (when sub-id (client/delete-subscription bl sub-id)))))

(deftest test-search-all-subscriptions-ven
  (if ven-subscriptions?
    (testing "VEN can search subscriptions (sees own)"
      (let [created (client/create-subscription ven1 (subscription-body "SearchVEN"))
            sub-id  (-> created :body :id)]
        (let [resp (client/get-subscriptions ven1)]
          (is (= 200 (:status resp)) "VEN search should succeed"))
        (when sub-id (client/delete-subscription bl sub-id))))
    (testing "VEN cannot search subscriptions (route disabled)"
      (let [resp (client/get-subscriptions ven1)]
        (is (= 404 (:status resp)) "VEN subscription route should return 404")))))

(deftest test-search-subscription-by-id-bl
  (testing "BL can get a subscription by ID"
    (let [created (client/create-subscription bl (subscription-body "ByIdBL"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/get-subscription-by-id bl sub-id)]
          (is (= 200 (:status resp)) "Get by ID should succeed"))
        (client/delete-subscription bl sub-id)))))

(deftest test-search-subscription-by-id-ven
  (if ven-subscriptions?
    (testing "VEN can get own subscription by ID"
      (let [created (client/create-subscription ven1 (subscription-body "ByIdVEN"))
            sub-id  (-> created :body :id)]
        (is (some? sub-id) "Need a subscription ID")
        (when sub-id
          (let [resp (client/get-subscription-by-id ven1 sub-id)]
            (is (= 200 (:status resp)) "VEN should get own subscription"))
          (client/delete-subscription bl sub-id))))
    (testing "VEN cannot get subscription by ID (route disabled)"
      (let [created (client/create-subscription bl (subscription-body "ByIdVEN"))
            sub-id  (-> created :body :id)]
        (when sub-id
          (let [resp (client/get-subscription-by-id ven1 sub-id)]
            (is (= 404 (:status resp)) "VEN subscription route should return 404"))
          (client/delete-subscription bl sub-id))))))

;; ---------------------------------------------------------------------------
;; Update subscriptions
;; ---------------------------------------------------------------------------

(deftest test-update-subscription-bl
  (testing "BL can update a subscription"
    (let [created (client/create-subscription bl (subscription-body "UpdateBL"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/update-subscription bl sub-id
                                               (subscription-body "UpdatedBL"))]
          (is (= 200 (:status resp)) "Update should succeed")
          (is (= "UpdatedBL" (-> resp :body :clientName))
              "clientName should be updated"))
        (client/delete-subscription bl sub-id)))))

(deftest test-update-subscription-ven
  (if ven-subscriptions?
    (testing "VEN can update own subscription"
      (let [created (client/create-subscription ven1 (subscription-body "UpdateVEN"))
            sub-id  (-> created :body :id)]
        (is (some? sub-id) "Need a subscription ID")
        (when sub-id
          (let [resp (client/update-subscription ven1 sub-id
                                                 (subscription-body "UpdatedVEN"))]
            (is (= 200 (:status resp)) "VEN update should succeed"))
          (client/delete-subscription bl sub-id))))
    (testing "VEN cannot update subscriptions (route disabled)"
      (let [created (client/create-subscription bl (subscription-body "UpdateVEN"))
            sub-id  (-> created :body :id)]
        (when sub-id
          (let [resp (client/update-subscription ven1 sub-id
                                                 (subscription-body "UpdatedVEN"))]
            (is (= 404 (:status resp)) "VEN subscription route should return 404"))
          (client/delete-subscription bl sub-id))))))

;; ---------------------------------------------------------------------------
;; Delete subscriptions
;; ---------------------------------------------------------------------------

(deftest test-delete-subscription-bl
  (testing "BL can delete a subscription"
    (let [created (client/create-subscription bl (subscription-body "DeleteBL"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/delete-subscription bl sub-id)]
          (is (= 200 (:status resp)) "Delete should succeed"))))))

(deftest test-delete-subscription-ven
  (if ven-subscriptions?
    (testing "VEN can delete own subscription"
      (let [created (client/create-subscription bl (subscription-body "DeleteVEN"))
            sub-id  (-> created :body :id)]
        (is (some? sub-id) "Need a subscription ID")
        (when sub-id
          (let [resp (client/delete-subscription ven1 sub-id)]
            (is (= 200 (:status resp)) "VEN delete should succeed")))))
    (testing "VEN cannot delete subscriptions (route disabled)"
      (let [created (client/create-subscription bl (subscription-body "DeleteVEN"))
            sub-id  (-> created :body :id)]
        (when sub-id
          (let [resp (client/delete-subscription ven1 sub-id)]
            (is (= 404 (:status resp)) "VEN subscription route should return 404"))
          (client/delete-subscription bl sub-id))))))

;; ---------------------------------------------------------------------------
;; Bad token tests
;; ---------------------------------------------------------------------------

(deftest ^:auth test-create-subscription-bad-token
  (testing "Bad token cannot create a subscription"
    (let [resp (client/create-subscription bad-token (subscription-body "BadTokenCreate"))]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest ^:auth test-search-subscriptions-bad-token
  (testing "Bad token cannot search subscriptions"
    (let [resp (client/get-subscriptions bad-token)]
      (is (= 403 (:status resp)) "Bad token should be forbidden"))))

(deftest ^:auth test-search-subscription-by-id-bad-token
  (testing "Bad token cannot get a subscription by ID"
    (let [created (client/create-subscription bl (subscription-body "BadTokenById"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/get-subscription-by-id bad-token sub-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-subscription bl sub-id)))))

(deftest ^:auth test-update-subscription-bad-token
  (testing "Bad token cannot update a subscription"
    (let [created (client/create-subscription bl (subscription-body "BadTokenUpdate"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/update-subscription bad-token sub-id
                                               (subscription-body "Hacked"))]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-subscription bl sub-id)))))

(deftest ^:auth test-delete-subscription-bad-token
  (testing "Bad token cannot delete a subscription"
    (let [created (client/create-subscription bl (subscription-body "BadTokenDelete"))
          sub-id  (-> created :body :id)]
      (is (some? sub-id) "Need a subscription ID")
      (when sub-id
        (let [resp (client/delete-subscription bad-token sub-id)]
          (is (= 403 (:status resp)) "Bad token should be forbidden"))
        (client/delete-subscription bl sub-id)))))

;; ---------------------------------------------------------------------------
;; Bad ID tests (404)
;; ---------------------------------------------------------------------------

(deftest test-search-subscription-bad-id
  (testing "Non-existent subscription ID returns 404"
    (let [resp (client/get-subscription-by-id bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-delete-subscription-bad-id
  (testing "Delete non-existent subscription returns 404"
    (let [resp (client/delete-subscription bl "nonexistent-id-12345")]
      (is (= 404 (:status resp)) "Should return 404 NOT_FOUND"))))

(deftest test-update-subscription-bad-id
  (testing "Update non-existent subscription returns 404 or 400"
    (let [resp (client/update-subscription bl "nonexistent-id-12345"
                                           (subscription-body "Ghost"))]
      (is (#{400 404} (:status resp))
          "Should return 404 NOT_FOUND or 400 BAD_REQUEST"))))

;; ---------------------------------------------------------------------------
;; Pagination (skip / limit)
;; ---------------------------------------------------------------------------

(deftest test-search-subscriptions-pagination
  (testing "Pagination with skip and limit"
    (let [s1 (client/create-subscription bl (subscription-body "PagSub1"))
          s2 (client/create-subscription bl (subscription-body "PagSub2"))
          s3 (client/create-subscription bl (subscription-body "PagSub3"))
          ids (mapv #(-> % :body :id) [s1 s2 s3])]
      (is (= 201 (:status s1)))
      (is (= 201 (:status s2)))
      (is (= 201 (:status s3)))

      (testing "limit=1 returns exactly 1"
        (let [resp (client/search-subscriptions bl {:limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip=1 skips first result"
        (let [all  (-> (client/get-subscriptions bl) :body count)
              resp (client/search-subscriptions bl {:skip 1})]
          (is (= 200 (:status resp)))
          (is (= (dec all) (count (:body resp))))))

      (testing "skip+limit together"
        (let [resp (client/search-subscriptions bl {:skip 1 :limit 1})]
          (is (= 200 (:status resp)))
          (is (= 1 (count (:body resp))))))

      (testing "skip too big returns empty"
        (let [resp (client/search-subscriptions bl {:skip 1000})]
          (is (= 200 (:status resp)))
          (is (= 0 (count (:body resp))))))

      ;; Clean up
      (doseq [id ids]
        (when id (client/delete-subscription bl id))))))

;; ---------------------------------------------------------------------------
;; Search by programID and clientName
;; ---------------------------------------------------------------------------

(deftest test-search-subscriptions-by-program-id
  (testing "Search subscriptions filtered by programID"
    (let [pid     (find-program-id)
          created (client/create-subscription bl (subscription-body "ProgFilter" pid))
          sub-id  (-> created :body :id)]
      (when sub-id
        (let [resp (client/search-subscriptions bl {:programID pid})]
          (is (= 200 (:status resp)))
          (is (>= (count (:body resp)) 1) "Should find subscriptions for this program"))
        (client/delete-subscription bl sub-id)))))

(deftest test-search-subscriptions-by-client-name
  (testing "Search subscriptions filtered by clientName"
    (let [created (client/create-subscription bl (subscription-body "UniqueClientName123"))
          sub-id  (-> created :body :id)]
      (when sub-id
        (let [resp (client/search-subscriptions bl {:clientName "UniqueClientName123"})]
          (is (= 200 (:status resp)))
          (is (>= (count (:body resp)) 1) "Should find subscription by clientName"))
        (client/delete-subscription bl sub-id)))))
