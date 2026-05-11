(ns openadr3.should-test
  "SHOULD-level OA3 conformance assertions.

  Tests in this namespace are tagged ^:should and are skipped by default
  via kaocha.plugin/should-gate. Opt in with
  :capabilities {:should-enforced? true} in test-config.edn.

  The OA3 OpenAPI spec marks error-response bodies as a Problem object
  (per RFC 7807) with :type / :title / :status / optionally :detail and
  :instance. Returning the right HTTP status alone is MUST; the body
  shape is SHOULD. These tests document the recommendation and let VTN
  authors see how their implementation measures up."
  (:require [openadr3.client.base :as client]
            [openadr3.common-test :refer [bl bad-token inter-suite-delay-ms]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once
  (fn [f]
    (Thread/sleep inter-suite-delay-ms)
    (f)))

;; ---------------------------------------------------------------------------
;; Problem-shape predicate (RFC 7807, OA3 OpenAPI Problem schema)
;; ---------------------------------------------------------------------------

(defn- problem-shape?
  "True if `body` looks like a Problem object: a map with at least
  :type and :title strings. :status may be absent or must equal the
  HTTP status. :detail and :instance are optional."
  [body status]
  (and (map? body)
       (string? (:type body))
       (string? (:title body))
       (or (not (contains? body :status))
           (= status (:status body)))))

(defn- shape-msg [body]
  (str "expected Problem-shaped body (RFC 7807); got " (pr-str body)))

;; ---------------------------------------------------------------------------
;; Error-body shape tests
;; ---------------------------------------------------------------------------

(deftest ^:should test-not-found-returns-problem-body
  (testing "404 NOT_FOUND error responses SHOULD be a Problem object"
    (let [resp (client/get-program-by-id bl "00000000-0000-0000-0000-000000000000")]
      (is (= 404 (:status resp)) "Should be 404")
      (is (problem-shape? (:body resp) (:status resp))
          (shape-msg (:body resp))))))

(deftest ^:should ^:auth test-bad-token-returns-problem-body
  (testing "403 FORBIDDEN error responses SHOULD be a Problem object"
    (let [resp (client/create-program bad-token {:programName "ShouldProblemBadToken"})]
      (is (= 403 (:status resp)) "Should be 403")
      (is (problem-shape? (:body resp) (:status resp))
          (shape-msg (:body resp))))))

(deftest ^:should test-conflict-returns-problem-body
  (testing "409 CONFLICT error responses SHOULD be a Problem object"
    (let [name "ShouldProblemConflict"
          r1   (client/create-program bl {:programName name})
          r2   (client/create-program bl {:programName name})]
      (is (= 201 (:status r1)) "First create should succeed")
      (is (= 409 (:status r2)) "Duplicate should conflict")
      (is (problem-shape? (:body r2) (:status r2))
          (shape-msg (:body r2)))
      (when-let [id (-> r1 :body :id)]
        (client/delete-program bl id)))))

(deftest ^:should test-bad-request-returns-problem-body
  (testing "400 BAD_REQUEST error responses SHOULD be a Problem object"
    ;; Update a non-existent program with a body that's syntactically valid
    ;; but references a non-existent ID — VTN should return 400 or 404, and
    ;; either way the body SHOULD be Problem-shaped.
    (let [resp (client/update-program bl
                                      "00000000-0000-0000-0000-000000000000"
                                      {:programName "ShouldProblemBadRequest"})]
      (is (#{400 404} (:status resp)) "Should be 400 or 404")
      (is (problem-shape? (:body resp) (:status resp))
          (shape-msg (:body resp))))))
