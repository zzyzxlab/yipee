(ns k8scvt.api-tests
  (:use clojure.test k8scvt.testutil)
  (:require [clj-http.client :as client]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.set :as set]
            [clj-yaml.core :as yaml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [k8scvt.file-import :as fi]
            [helm.core :as helm]
            [engine.core :refer [ppwrap]])
  (:import [java.util
            Base64]
           [java.nio.charset
            StandardCharsets]
           [java.io
            ByteArrayInputStream]
           [org.apache.commons.io IOUtils]
           [org.apache.commons.compress.compressors.gzip
            GzipCompressorInputStream]
           [org.apache.commons.compress.archivers.tar
            TarArchiveEntry TarArchiveInputStream]))

(deftest bad-endpoint
  (with-server
    (let [testurl (format "%s/bogus" base-url)
          {:keys [status]} (client/get testurl {:throw-exceptions false})]
      (is (= 404 status)))))

(defn get-tar-entries [chart]
  (let [bais (ByteArrayInputStream. chart)
        gzis (GzipCompressorInputStream. bais)
        tais (TarArchiveInputStream. gzis)]
    (loop [result []]
      (if-let [entry (.getNextTarEntry tais)]
        (let [buf (byte-array (.getSize entry))
              _ (.read tais buf 0 (alength buf))
              str (String. buf StandardCharsets/UTF_8)]
          (recur (conj result {:name (.getName entry) :contents str})))
        result))))

(defn errs-for-element [rpt element]
  (reduce (fn [cnt msg]
            (if (string/includes? msg element) (+ cnt 1) cnt)) 0 rpt))

(defn tgz-bundle [fname]
  (.encodeToString (Base64/getEncoder)
                   (IOUtils/toByteArray (io/input-stream fname))))

(deftest test-flat-direct
  (with-server
    (let [toflat (format "%s/k2f" base-url)
          tok8s (format "%s/f2k" base-url)
          tok8sbundle (format "%s/f2kbundle" base-url)
          tohelmbundle (format "%s/f2hbundle" base-url)
          data (slurp "test/k8scvt/testdata/k8s-service.yaml")
          arg-data {:accept :json
                    :content-type :json
                    :body data}
          {fbody :body} (client/post toflat arg-data)
          flatdata (json/read-str fbody :key-fn keyword)
          flatbody (assoc arg-data :body fbody)
          {kbody :body} (client/post tok8s flatbody)
          {kb-body :body kb-status :status} (client/post tok8sbundle flatbody)
          {hb-body :body hb-status :status} (client/post tohelmbundle flatbody)
          k8sdata (yaml/parse-string kbody)]
        (is (every? #(contains? flatdata %)
                    [:service :port-mapping :k8s-service :app-info]))
        (is (= "Service" (:kind k8sdata)))
        (is (every? #(contains? k8sdata %) [:apiVersion :metadata :spec]))
        (is (= 201 kb-status))
        (is (= 201 hb-status))
        ;; all k8s bundle entries should be k8s objects
        (doseq [entry (get-tar-entries (helm/decode-bytes kb-body))]
          (let [k8sobj (yaml/parse-string (:contents entry))]
            (is (every? #(contains? k8sobj %)
                        [:kind :apiVersion :metadata :spec]))))
        ;; helm bundle should contain "Chart.yaml", "values.yaml" and
        ;; one or more k8s manifests
        (let [helmfiles (atom #{})]
          (doseq [entry (get-tar-entries (helm/decode-bytes hb-body))]
            (let [ename (:name entry)]
              (swap! helmfiles conj ename)
              (if (not (or (= ename "unknown/Chart.yaml")
                           (= ename "unknown/values.yaml")))
                (let [k8sobj (yaml/parse-string (:contents entry))]
                  (is (every? #(contains? k8sobj %)
                              [:kind :apiVersion :metadata :spec]))))))
          (is (> (count @helmfiles) 2))
          (is (contains? @helmfiles "unknown/Chart.yaml"))
          (is (contains? @helmfiles "unknown/values.yaml"))))))

(deftest test-bundle-to-flat
  (with-server
    (doseq [tarfile ["test/k8scvt/testdata/bday.tgz"
                     "test/k8scvt/testdata/composite.tgz"]]
      (let [b2furl (format "%s/kbundle2f" base-url)
            data (tgz-bundle tarfile)
            arg-data {:accept :json
                      :content-type "text/plain"
                      :body data}
            {:keys [status body]} (client/post b2furl arg-data)]
        (is (= 201 status))
        (let [flat (json/read-str body :key-fn keyword)]
          (is (= 3 (count (:deployment-spec flat))))
          (is (= 3 (count (:replication flat)))))))
    (let [b2furl (format "%s/kbundle2f" base-url)
          baddata (tgz-bundle "test/k8scvt/testdata/badcomposite.tgz")
          arg-data {:accept :json
                    :content-type "text/plain"
                    :body baddata
                    :throw-exceptions false}
          {:keys [status body]} (client/post b2furl arg-data)]
      (is (= 422 status))
      (let [errs (string/split body #"\n")]
        (is (= 9 (count errs)))
        (is (= 4 (errs-for-element errs "composite1.yaml[6]")))
        (is (= 3 (errs-for-element errs "redmine-deploy.yaml")))
        (is (= 0 (errs-for-element errs "composite2.yaml[0]")))))))

(deftest test-helm-settings
  (with-server
    (let [flatfile (test-slurp "helm-flat-simple-service.json")
          with-settings (test-slurp "helm-flat-simple-service-settings.json")
          nerdfile (test-slurp "helm-flat-simple-service.yaml")
          nerd-with-settings (test-slurp
                              "helm-flat-simple-service-settings.yaml")
          url (str base-url "/f2hnerd")
          url-with-settings (str base-url "/f2hnerd/env+ports")
          all-url (str base-url "/f2hnerd/all")
          arg-data {:accept :json :content-type "json"}
          {statusall :status bodyall :body} (client/post
                                             url
                                             (assoc arg-data :body flatfile))
          {status-ep :status body-ep :body} (client/post
                                             url
                                             (assoc arg-data
                                                    :body with-settings))
          {status-epu :status body-epu :body} (client/post
                                               url-with-settings
                                               (assoc arg-data :body flatfile))
          {status-allu :status body-allu :body} (client/post
                                                 all-url
                                                 (assoc arg-data :body
                                                        with-settings))]
      (is (= 201 statusall status-ep status-epu status-allu))
      (is (= bodyall body-allu))
      (is (= body-ep body-epu))
      (is (= (apply str (sort bodyall)) (apply str (sort nerdfile))))
      (is (= (apply str (sort body-ep)) (apply str (sort nerd-with-settings))))
      (is (not= bodyall body-ep)))))

(defn- wrap [name yaml] {:name name :yaml yaml})

(deftest test-diff-apis
  (with-server
    (let [parent (test-slurp "helm_model_1.yaml")
          child (test-slurp "helm_model_2.yaml")
          url (str base-url "/m2d")
          arg-data {:accept :json :content-type "json"}
          {status :status body :body} (client/post
                                       url
                                       (assoc
                                        arg-data
                                        :body
                                        (json/write-str
                                         {:parent (wrap "parent" parent)
                                          :children [(wrap "child" child)]})))]
      (is (= 201 status))
      (is (= (sort (string/split body #"[\n]"))
             (sort (string/split (test-slurp "diff-api-output.txt") #"[\n]")))))
    (let [chart-name "chart"
          models (map (fn [[name file]]
                        {:name name :yaml (slurp (test-file-name file))})
                      [["sd" "sd.yaml"] ["sd2" "sd2.yaml"]])
          url (str base-url "/m2hbundle")
          arg-data {:accept :json :content-type "json"}
          {status :status body :body} (client/post
                                       url
                                       (assoc
                                        arg-data
                                        :body
                                        (json/write-str
                                         {:chart-name chart-name
                                          :models models})))
          entries (get-tar-entries (helm/decode-bytes
                                    (test-slurp "m2hbundle-api-output.tgz64")))
          ret-entries (get-tar-entries (helm/decode-bytes body))]
      (is (= 201 status))
      (doseq [entry entries]
        (doseq [rentry ret-entries]
          (when (= (:name entry) (:name rentry))
            (is (= (:contents entry) (:contents rentry)))))))))

(defn fetch-annos [jsonstr]
  ;; return a set containing the value objects from the given json
  ;; annotations.
  (set
   (map #(:value %)
        (filter #(= "ui" (:key %))
                (:annotation (json/read-str jsonstr :key-fn keyword))))))

(defn f2k2f [flatstr]
  (let [toflat (format "%s/k2f" base-url)
        tok8s (format "%s/f2k" base-url)
        arg-data {:accept :json
                  :content-type :json
                  :body flatstr}
        {kbody :body} (client/post tok8s arg-data)
        {newflatstr :body} (client/post toflat (assoc arg-data :body kbody))]
    newflatstr))

(deftest test-layout-annos
  (with-server
    (let [flat (slurp "test/k8scvt/testdata/yipee-flat.json")
          newflat (f2k2f flat)
          badflat (slurp "test/k8scvt/testdata/bad-layout-anno.json")
          newbadflat (f2k2f badflat)
          origannos (fetch-annos flat)
          newannos (fetch-annos newflat)
          badannos (fetch-annos badflat)
          newbadannos (fetch-annos newbadflat)]
      (is (= (count origannos) (count newannos)))
      ;; Assert that we have all the original x/y values.
      ;; We can't compare much else since all the IDs will be different
      (is (= (count (set/intersection origannos newannos))
             (count origannos)))
      ;; the "bad-layout-annos" file contains a single unknown-kind and
      ;; its associated layout annotation.  Since the unknown-kind object
      ;; has no name attribute, we expect the layout annotation to be dropped
      (is (= 1 (count badannos)))
      (is (= 0 (count newbadannos))))))
