(ns skylobby.rapid
  "Utilities for interacting with rapid and its files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [skylobby.fs :as fs]
    [skylobby.lua :as lua]
    [skylobby.spring.script :as spring-script]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.io ByteArrayInputStream)
    (java.util.zip GZIPInputStream)
    (javax.imageio ImageIO)))


(set! *warn-on-reflection* true)


; https://springrts.com/wiki/Rapid


(def md5-codec
  (b/compile-codec
    (b/blob :length 16)
    (fn [^String md5-string]
      (.toByteArray
        (java.math.BigInteger. md5-string 16)))
    (fn [md5-bytes]
      (apply str (map (partial format "%02x") md5-bytes)))))


; https://springrts.com/wiki/Rapid#an_index_archive:_.sdp
(def sdp-line
  (b/header
    (b/ordered-map
      :filename-length :ubyte)
    (fn [{:keys [filename-length]}]
      (b/ordered-map
        :filename (b/string "ISO-8859-1" :length filename-length)
        :md5 md5-codec
        :crc32 :uint-le
        :file-size :uint-le))
    (constantly nil) ; TODO writing SDP files
    :keep-header? false))


(defn decode-sdp [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    {:file f
     :items (b/decode (b/repeated sdp-line) gz)}))

(defn sdp-filename [rapid-hash]
  (when rapid-hash
    (str rapid-hash ".sdp")))

(defn sdp-file
  [root sdp-filename]
  (when sdp-filename
    (io/file root "packages" sdp-filename)))

(defn sdp-files
  [root]
  (log/debug "Loading sdp file names from" root)
  (let [packages-root (io/file root "packages")]
    (or
      (when (.exists packages-root)
        (seq (.listFiles packages-root)))
      [])))

(defn file-in-pool
  [root md5]
  (let [pool-dir (subs md5 0 2)
        pool-file (str (subs md5 2) ".gz")]
    (io/file root "pool" pool-dir pool-file)))

(defn slurp-from-pool
  [root md5]
  (let [f (file-in-pool root md5)]
    (with-open [is (io/input-stream f)
                gz (GZIPInputStream. is)]
      (slurp gz))))

(defn slurp-bytes-from-pool
  [root md5]
  (let [f (file-in-pool root md5)]
    (with-open [is (io/input-stream f)
                gz (GZIPInputStream. is)]
      (u/slurp-bytes gz))))

(defn inner
  [root decoded-sdp inner-filename]
  (if-let [inner-details (->> decoded-sdp
                              :items
                              (filter (comp #{inner-filename} :filename))
                              first)]
    (assoc inner-details :content-bytes (slurp-bytes-from-pool root (:md5 inner-details)))
    (log/warn "No such inner rapid file"
              (pr-str {:package-file (:file decoded-sdp)
                       :inner-filename inner-filename}))))

(defn root-from-sdp
  "Returns the spring root for the given sdp file."
  [f]
  (-> f fs/parent-file fs/parent-file))

(defn rapid-inner [sdp-file inner-filename]
  (let [root (root-from-sdp sdp-file)]
    (inner
      root
      (decode-sdp sdp-file)
      inner-filename)))

(defn sdp-hash [^java.io.File sdp-file]
  (-> (fs/filename sdp-file)
      (string/split #"\.")
      first))


(def repos-url "http://repos.springrts.com/repos.gz")


(defn repos
  [root]
  (log/debug "Loading rapid repo names")
  (->> (fs/list-files (io/file root "rapid" "repos.springrts.com"))
       seq
       (filter fs/is-directory?)
       (map fs/filename)))

(defn rapid-versions [f]
  (try
    (with-open [is (io/input-stream f)
                gz (GZIPInputStream. is)]
      (->> gz
           slurp
           (string/split-lines)
           (map
             (fn [line]
               (let [[id commit detail version] (string/split line #",")]
                 {:id id
                  :hash commit
                  :detail detail
                  :version version})))))
    (catch Exception e
      (log/error e "Error reading rapid versions from" f)
      (throw e))))


(defn package-versions
  [root]
  (-> root
      (io/file "rapid" "packages.springrts.com" "versions.gz")
      (rapid-versions)))


(defn version-file
  [root repo]
  (io/file root "rapid" "repos.springrts.com" repo "versions.gz"))

(defn versions
  [root repo]
  (log/debug "Loading rapid versions for repo" repo)
  (try
    (-> root
        (io/file "rapid" "repos.springrts.com" repo "versions.gz")
        (rapid-versions))
    (catch Exception e
      (log/error e "Error loading rapid versions"))))


(defn- try-inner-lua
  ([f filename]
   (try-inner-lua f filename nil))
  ([f filename {:keys [quiet] :or {quiet true}}]
   (try
     (when-let [inner (rapid-inner f filename)]
       (let [contents (slurp (:content-bytes inner))]
         (when-not (string/blank? contents)
           (lua/read-modinfo contents))))
     (catch Exception e
       (if quiet
         (do
           (log/trace e "Error reading" filename "in" f)
           (log/warn e "Error reading" filename "in" f))
         (log/warn e "Error reading" filename "in" f))))))

(defn- try-inner-script
  [f filename]
  (try
    (when-let [inner (rapid-inner f filename)]
      (let [contents (slurp (:content-bytes inner))]
        (when-not (string/blank? contents)
          (spring-script/parse-script contents))))
    (catch Exception e
      (log/warn e "Error reading" filename "in" f))))


(def scenario-prefix
  "luamenu/configs/gameconfig/byar/scenarios/")

(defn read-sdp-mod
  ([^java.io.File f]
   (read-sdp-mod f nil))
  ([^java.io.File f {:keys [modinfo-only]}]
   (let [modinfo (try-inner-lua f "modinfo.lua" {:quiet false})] ; TODO don't parse the .sdp over and over
     (when-not modinfo
       (throw (ex-info "Could not read mod" {:file f})))
     (merge
       {::fs/source :rapid
        :file f
        :modinfo modinfo}
       (when-not modinfo-only
         (when modinfo
           (merge
             {:modoptions (try-inner-lua f "modoptions.lua" {:quiet false})
              :engineoptions (try-inner-lua f "engineoptions.lua")
              :luaai (try-inner-lua f "luaai.lua")
              :sidedata (or (try-inner-lua f "gamedata/sidedata.lua")
                            (try-inner-script f "gamedata/sidedata.tdf")
                            (u/postprocess-byar-units-en
                              (try-inner-lua f "language/units_en.lua")))}
             (when (= "BYAR Chobby" (:name modinfo))
               (log/info "Loading scenarios from chobby")
               (let [{:keys [items] :as decoded} (decode-sdp f)
                     root (root-from-sdp f)
                     scenario-items (filter
                                      #(string/starts-with? (:filename %) scenario-prefix)
                                      items)
                     scenario-lua (filter
                                    #(string/ends-with? (:filename %) ".lua")
                                    scenario-items)
                     scenario-names (->> scenario-lua
                                         (map
                                          (fn [{:keys [filename]}]
                                            (let [without-path (last (string/split filename #"/"))]
                                              (subs without-path 0 (string/last-index-of without-path ".")))))
                                         set)]
                 (log/info "Found" (count scenario-lua) "scenarios")
                 {:scenarios
                  (mapv
                    (fn [scenario]
                      (let [lua-path (str scenario-prefix scenario ".lua")
                            img-path (str scenario-prefix scenario ".jpg")]
                        {:scenario scenario
                         :lua (try-inner-lua f lua-path)
                         :image-file
                         (try
                           (let [image-bytes (:content-bytes (inner root decoded img-path))
                                 image (with-open [bais (ByteArrayInputStream. image-bytes)]
                                         (ImageIO/read bais))
                                 file (fs/file (fs/app-root) "scenarios" (str scenario ".png"))]
                             (fs/make-parent-dirs file)
                             (fs/write-image-png image file)
                             file)
                           (catch Exception e
                             (log/error e "Error loading scenario image" img-path)
                             nil))}))
                    scenario-names)})))))))))


(defn copy-package [source-sdp-file dest-spring-root]
  (log/info "Copying rapid package from" source-sdp-file "into" dest-spring-root)
  (let [{:keys [items]} (decode-sdp source-sdp-file)
        source-spring-root (fs/parent-file (fs/parent-file source-sdp-file))]
    (log/info "Copying" (count items))
    (doseq [{:keys [filename md5]} items]
      (try
        (let [source-file (file-in-pool source-spring-root md5)
              dest-file (file-in-pool dest-spring-root md5)]
          (log/info "Copying" source-file "(" filename ") to" dest-file)
          (fs/copy source-file dest-file))
        (catch Exception e
          (log/warn e "Error copying rapid pool file for" md5 "(" filename ")"))))
    (log/info "Copying package file" source-sdp-file)
    (fs/copy source-sdp-file (io/file dest-spring-root "packages" (fs/filename source-sdp-file)))))
