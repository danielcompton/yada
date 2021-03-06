;; Copyright © 2016, JUXT LTD.

(ns yada.dev.manual
  (:require
   [clojure.core.async :as a]
   [camel-snake-kebab.core :refer [->kebab-case-keyword]]
   [clojure.java.io :as io]
   [clojure.tools.logging :refer :all]
   [hiccup.core :refer [html]]
   [ring.util.mime-type :refer (ext-mime-type)]
   [schema.core :as s]
   [yada.yada :refer [handler resource as-resource]]
   [yada.handler :refer [prepend-interceptor]])
  (:import [org.asciidoctor Asciidoctor$Factory]
           [org.asciidoctor.ast Document]
           [org.asciidoctor.extension JavaExtensionRegistry DocinfoProcessor]))

(defn ->author [author]
  {:first-name (.getFirstName author)
   :full-name (.getFullName author)
   :initials (.getInitials author)
   :last-name (.getLastName author)
   :middle-name (.getMiddleName author)})

(defn get-header
  "Extract an adoc document header into a map"
  [engine content]
  (let [m (.readDocumentHeader engine content)]
    {:attributes (reduce-kv (fn [acc k v] (assoc acc (->kebab-case-keyword k) v)) {} (into {} (.getAttributes m)))
     :author (->author (.getAuthor m))
     :authors (map ->author (.getAuthors m))
     :document-title
     (let [t (.getDocumentTitle m)]
       {:main (.getMain t)
        :subtitle (.getSubtitle t)
        :combined (.getCombined t)
        :sanitized? (.isSanitized t)})
     :page-title (.getPageTitle m)
     :revision-info (let [ri (.getRevisionInfo m)]
                      {:date (.getDate ri)
                       :number (.getNumber ri)
                       :remark (.getRemark ri)})}))

(defn generate-favicons []
  (html
   [:link {:rel "icon" :type "image/png" :href "/img/favicon/favicon.png"}]
   (for [size [16 92 192 32]              ; firefox needs 32x32 last
         :let [s (format "%dx%d" size size)]]
     [:link {:rel "icon" :type "image/png" :href (format "/img/favicon/favicon-%s.png" s) :sizes s}])

   ;; Apple touch icons
   [:link {:rel "apple-touch-icon" :href "/img/favicon/favicon-60x60.png"}]
   (for [size [60 76 120 152]
         :let [s (format "%dx%d" size size)]]
     [:link {:rel "apple-touch-icon" :href (format "/img/favicon/favicon-%s.png" s) :sizes s}])))

(defn register-docinfo-processor! [engine]
  (infof "Register docinfo processor")
  (let [jer (.javaExtensionRegistry engine)
        p ^DocinfoProcessor
        (proxy [DocinfoProcessor] []
          (process [^Document doc] (generate-favicons)))]
    (.docinfoProcessor jer ^DocinfoProcessor p)))

(defn asciidoc->html [fl {:keys [toc]}]
  (fn [ctx]
    (let [engine (Asciidoctor$Factory/create)
          _ (register-docinfo-processor! engine)
          ]
      (.convertFile
       engine fl
       (..
        (org.asciidoctor.OptionsBuilder/options)
        (backend "html5")
        (headerFooter true)             ; standalone doc

        ;; "We recommend you to set SAFE safe
        ;; mode when rendering AsciiDoc documents
        ;; using AsciidoctorJ to have almost all
        ;; Asciidoctor features such as icons,
        ;; include directive or retrieving
        ;; content from URIs enabled."

        ;; But safe allows us to specify the CSS dir
        (safe org.asciidoctor.SafeMode/UNSAFE)
        (toFile false)             ; otherwise doesn't return a string
        (attributes
         (..
          (org.asciidoctor.AttributesBuilder/attributes)
          (tableOfContents (case toc true org.asciidoctor.Placement/LEFT false))
          (imagesDir "img")
          (styleSheetName "juxt.css")
          #_(stylesDir "resources/asciidoctor/stylesheets")
          (sectionNumbers true))))))))

(defn get-chapters [dir]
  (keep second
       (map (partial re-matches #"1. link:(.+).html.*")
            (line-seq (io/reader (io/file dir "index.adoc"))))))

(defn routes []
  [""
   [
    ["/manual/intro-examples/"
     [["hello" (handler "Hello World!")]
      ["index.html" (handler (io/file "doc/intro-examples/index.html"))]
      ["dir/" (handler (io/file "dev/resources/static"))]
      ["nil" (handler nil)]
      ["dice" (handler #(inc (rand-int 6)))]
      ["sse-dice" (let [ch (a/chan 10)]
                    (a/go-loop []
                      (when (a/>!! ch (str (inc (rand-int 6))))
                        (a/<!! (a/timeout 250))
                        (recur)))
                    (handler ch))]]]
    ["/manual/img/"
     (resource
      {:path-info? true
       :methods {}
       :sub-resource
       ;; We don't know the file type until request time. So we
       ;; create the resource just-in-time.
       (fn [ctx]
         ;; Interceptors haven't run yet.

         ;; TODO: Sub-resources should be created by an
         ;; interceptor. That way a user can elect for certain
         ;; custom interceptors to be run prior to sub-resource
         ;; creation.
         (resource
          {:properties (fn [ctx]
                         ;; Now the interceptors have been run. If
                         ;; we did the TODO above we wouldn't need
                         ;; to resolve the app-state in the
                         ;; properties callback.
                         (let [imgdir (io/file "doc")
                               fl (io/file imgdir (-> ctx :request :path-info))]
                           {:exists? (.exists fl)
                            ::file fl}))
           :produces (fn [ctx] (ext-mime-type (.getName (-> ctx :properties ::file))))
           :methods
           {:get
            {:response
             (fn [ctx] (-> ctx :properties ::file))}}

           ;; Inherit the interceptor chain from the parent
           :interceptor-chain (:interceptor-chain ctx)
           }))})]

    [["/manual/index.html"]
     (resource
      {:id ::manual
       :methods
       {:get
        {:produces {:media-type "text/html" :charset "UTF-8"}
         :response (asciidoc->html (io/file "doc" "yada-manual.adoc")
                                   {:toc true})}}})]

    [["/index.html"]
     (resource
      {:id ::manual
       :methods
       {:get
        {:produces {:media-type "text/html" :charset "UTF-8"}
         :response (asciidoc->html (io/file "doc" "index.adoc")
                                   {})}}})]

    ["/adoc/"
     (as-resource
      (io/file "doc"))]]])
