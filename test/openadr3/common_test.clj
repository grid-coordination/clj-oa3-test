(ns openadr3.common-test
  (:require [openadr3.api :as api]))

(def apispecfile_3_0_0 "resources/openadr3-specification/3.0.0/openadr3.yaml")
(def apispecfile_3_0_1 "resources/openadr3-specification/3.0.1/openadr3.yaml")
(def apispecfile_3_1_0 "resources/openadr3-specification/3.1.0/openadr3.yaml")

(def VTN-url "http://localhost:8080/openadr3/3.1.0")

(def oa3spec
  (api/read-openapi-spec apispecfile_3_1_0))

(def ven1
  (api/create-ven-client apispecfile_3_1_0 "ven_token" VTN-url))

(def ven2
  (api/create-ven-client apispecfile_3_1_0 "ven_token2" VTN-url))

(def bl
  (api/create-bl-client apispecfile_3_1_0 "bl_token" VTN-url))
