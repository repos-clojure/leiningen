(ns leiningen.core.project
  "Read project.clj files."
  (:refer-clojure :exclude [read])
  (:require [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [ordered.map :as ordered]
            [cemerick.pomegranate :as pomegranate]
            [leiningen.core.user :as user]
            [leiningen.core.classpath :as classpath])
  (:import (clojure.lang DynamicClassLoader)))

(defn- unquote-project
  "Inside defproject forms, unquoting (~) allows for arbitrary evaluation."
  [args]
  (walk/walk (fn [item]
               (cond (and (seq? item) (= `unquote (first item))) (second item)
                     ;; needed if we want fn literals preserved
                     (or (seq? item) (symbol? item)) (list 'quote item)
                     :else (unquote-project item)))
             identity
             args))

(def defaults {:source-paths ["src"]
               :resource-paths ["resources"]
               :test-paths ["test"]
               :native-path "native"
               :compile-path "target/classes"
               :target-path "target"
               :repositories (ordered/ordered-map
                              "central" {:url "http://repo1.maven.org/maven2"}
                              ;; TODO: point to releases-only before 2.0 is out
                              "clojars" {:url "http://clojars.org/repo/"})
               :jar-exclusions [#"^\."]
               :uberjar-exclusions [#"^META-INF/DUMMY.SF"]})

(defn ^:internal add-repositories
  "Public only for macroexpansion purposes, :repositories needs special
  casing logic for merging default values with user-provided ones."
  [{:keys [omit-default-repositories repositories] :as project}]
  (assoc project :repositories
         (into (if-not omit-default-repositories
                 (:repositories defaults)
                 (ordered/ordered-map))
                (for [[id repo] repositories]
                  [id (if (string? repo) {:url repo} repo)]))))

(defmacro defproject
  "The project.clj file must either def a project map or call this macro."
  [project-name version & {:as args}]
  `(let [args# ~(unquote-project args)]
     (def ~'project
       (merge defaults (dissoc (add-repositories args#)
                               ;; Strip out aliases for normalization.
                               :eval-in-leiningen :deps)
              {:name ~(name project-name)
               :group ~(or (namespace project-name)
                           (name project-name))
               :version ~version
               :dependencies (ordered/ordered-map
                              (or (:dependencies args#) (:deps args#)))
               :compile-path (or (:compile-path args#)
                                 (.getPath (io/file (:target-path args#)
                                                    "classes")))
               :plugins (ordered/ordered-map (:plugins args#))
               :root ~(.getParent (io/file *file*))
               :eval-in (or (:eval-in args#)
                            (if (:eval-in-leiningen args#)
                              :leiningen
                              :subprocess))}))))

(defn- absolutize [root path]
  (str (if (.startsWith path "/")
         path
         (io/file root path))))

(defn- absolutize-path [{:keys [root] :as project} key]
  (cond (re-find #"-path$" (name key))
        (update-in project [key] (partial absolutize root))

        (re-find #"-paths$" (name key))
        (update-in project [key] (partial map (partial absolutize root)))

        :else project))

(defn absolutize-paths [project]
  (reduce absolutize-path project (keys project)))

(def default-profiles
  "Profiles get merged into the project map. The :dev and :user
  profiles are active by default."
  (atom {:default {:resource-paths ["dev-resources"]
                   :prep-tasks ["javac" "compile"]}
         :test {}
         :debug {:debug true}}))

(defn- profile-key-merge
  "Merge profile values into the project map based on their type."
  [result latter]
  (cond (-> result meta :displace)
        latter

        (-> latter meta :replace)
        latter

        (and (map? result) (map? latter))
        (merge-with profile-key-merge latter result)

        (and (set? result) (set? latter))
        (set/union latter result)

        (and (coll? result) (coll? latter))
        (concat latter result)

        :else (doto latter (println "has a type mismatch merging profiles."))))

(defn merge-profile [project profile]
  (merge-with profile-key-merge project profile))

(defn- lookup-profile [profiles profile-name]
  (let [result (profiles profile-name)]
    ;; TODO: only warn when profiles are explicitly requested
    (when (and (nil? result) (not (#{:default :dev :user :test} profile-name)))
      (println "Warning: profile" profile-name "not found."))
    (if (keyword? result)
      (recur profiles result)
      result)))

(defn- profiles-for
  "Read profiles from a variety of sources.

  We check Leiningen's defaults, the profiles.clj file in ~/.lein/profiles.clj,
  the profiles.clj file in the project root, and the :profiles key from the
  project map."
  [project profiles-to-apply]
  (when (some (comp :repositories val) (user/profiles))
    (println "WARNING: :repositories detected in user-level profile!")
    (println "See https://github.com/technomancy/leiningen/wiki/Repeatability"))
  (let [profiles (merge @default-profiles (user/profiles) (:profiles project))]
    ;; We reverse because we want profile values to override the
    ;; project, so we need "last wins" in the reduce, but we want the
    ;; first profile specified by the user to take precedence.
    (map (partial lookup-profile profiles) (reverse profiles-to-apply))))

(defn merge-profiles
  "Look up and merge the given profile names into the project map."
  [project profiles-to-apply]
  (let [merged (reduce merge-profile project
                       (profiles-for project profiles-to-apply))]
    (with-meta (absolutize-paths merged)
      {:without-profiles (absolutize-paths project)})))

(defn ensure-dynamic-classloader []
  (let [thread (Thread/currentThread)
        cl (.getContextClassLoader thread)]
    (when-not (pomegranate/can-modify? cl)
      (.setContextClassLoader thread (DynamicClassLoader. cl)))))

(defn load-plugins
  ([project plugin-key]
    (ensure-dynamic-classloader)
    (classpath/resolve-dependencies plugin-key project :add-classpath? true))
  ([project] (load-plugins project :plugins)))

(defn- load-hooks [project]
  (doseq [n (:hooks project)]
    (try (require n)
         (when-let [activate (ns-resolve n 'activate)]
           (activate))
         (catch Throwable e
           (binding [*out* *err*]
             (println "Error: problem requiring" n "hook"))
           (throw e)))))

;; TODO: port this from lein-precate; other plugins are sure to want it
(defn denormalize [project])

(defn apply-middleware [project middleware-name]
  (when-let [m-ns (namespace middleware-name)]
    (require (symbol m-ns)))
  ((resolve middleware-name) project))

(defn read
  "Read project map out of file, which defaults to project.clj."
  ([file profiles]
     (locking read
       (binding [*ns* (find-ns 'leiningen.core.project)]
         (load-file file))
       (let [project (resolve 'leiningen.core.project/project)]
         (when-not project
           (throw (Exception. "project.clj must define project map.")))
         ;; return it to original state
         (ns-unmap 'leiningen.core.project 'project)
         (let [{:keys [eval-in] :as project} (merge-profiles @project profiles)]
           (when (= :leiningen eval-in)
             (doseq [path (classpath/get-classpath project)]
               (pomegranate/add-classpath path)))
           (load-plugins project)
           (load-hooks project)
           (reduce apply-middleware project (:middleware project))))))
  ([file] (read file [:dev :user :default]))
  ([] (read "project.clj")))
