;; Copyright © 2015, JUXT LTD.

(ns yada.cors-test
  (:require
   [clojure.test :refer :all :exclude [deftest]]
   [ring.mock.request :as mock]
   [schema.test :refer [deftest]]
   [yada.handler :refer [handler]]
   [yada.resource :refer [resource as-resource]]))

(deftest allow-origin-test
  (testing "No Origin header means no CORS processing at all"
    (let [res (resource {:methods {:get "Hello"}
                         :access-control {:allow-origin "*"}})
          h (handler res)
          resp @(h (mock/request :get "/"))]
      (is (not (contains? (set (keys (:headers resp))) "access-control-allow-origin")))))

  (testing "Wildcard origin"
    (let [res (resource {:methods {:get "Hello"}
                         :access-control {:allow-origin "*"}})
          h (handler res)
          resp @(h (-> (mock/request :get "/")
                       (update :headers conj ["origin" "http://localhost"])))]
      (is (contains? (set (keys (:headers resp))) "access-control-allow-origin"))
      (is (= "*" (get-in resp [:headers "access-control-allow-origin"])))))

  (testing "Specific origin"
    (let [res (resource {:methods {:get "Hello"}
                         :access-control {:allow-origin "http://localhost"}})
          h (handler res)
          resp @(h (-> (mock/request :get "/")
                       (update :headers conj ["origin" "http://localhost"])))]
      (is (contains? (set (keys (:headers resp))) "access-control-allow-origin"))
      (is (= "http://localhost" (get-in resp [:headers "access-control-allow-origin"])))))

  (testing "Specific origin with choice"
    (let [res (resource {:methods {:get "Hello"}
                         :access-control {:allow-origin ["http://localhost"
                                                         "http://yada.juxt.pro"
                                                         ]}})
          h (handler res)
          resp @(h (-> (mock/request :get "/")
                       (update :headers conj ["origin" "http://localhost"])))]
      (is (contains? (set (keys (:headers resp))) "access-control-allow-origin"))
      (is (= "http://localhost" (get-in resp [:headers "access-control-allow-origin"])))))

  (testing "Specific origin falling outside choice"
    (let [res (resource {:methods {:get "Hello"}
                         :access-control {:allow-origin ["http://localhost"
                                                         "http://yada.juxt.pro"
                                                         ]}})
          h (handler res)
          resp @(h (-> (mock/request :get "/")
                       ;; HT to @bbatsov
                       (update :headers conj ["origin" "http://acme.ro"])))]
      (is (not (contains? (set (keys (:headers resp))) "access-control-allow-origin")))
      )))
