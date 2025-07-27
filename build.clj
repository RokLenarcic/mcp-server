(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [clojure.tools.deps :as t]
            [deps-deploy.deps-deploy :as deps-deploy]))

(def lib 'org.clojars.roklenarcic/mcp-server)
(def version (format "0.2.%s" (b/git-count-revs nil)))

(defn create-opts [cli-opts aliases]
      (let [target (:target cli-opts "target")
            scm (cond (:tag cli-opts) {:tag (:tag cli-opts)}
                      version {:tag (str "v" version)})]
           (merge {:basis (b/create-basis {:aliases aliases})
                   :lib lib :version version :class-dir (str target "/classes") :target target
                   :src-dirs ["src/main"] :resource-dirs ["resources"] :scm scm
                   :jar-file (format "%s/%s-%s.jar" target (name lib) version)}
                  cli-opts)))

(defn clean [opts] (b/delete {:path "target"}) opts)

(defn- run-tests [opts]
       (let [cmd (b/java-command (assoc opts
                                        :jvm-opts (:jvm-opts (t/combine-aliases (:basis opts) [:test]))
                                        :main 'clojure.main
                                        :main-args ["-m" "cognitect.test-runner"]))
             {:keys [exit]} (b/process cmd)]
            (when-not (zero? exit)
                      (throw (ex-info "Tests failed" {}))))
       opts)

(defn test "Run the tests." [opts]
      (run-tests (create-opts opts [:test])))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
      (test opts)
      (-> (create-opts opts [])
          (clean)
          (doto (b/write-pom))
          (b/jar)))

(defn install "Install the JAR locally." [opts]
      (-> (create-opts opts [])
          (b/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
      (let [{:keys [jar-file]} (create-opts opts [])]
           (deps-deploy/deploy {:installer :remote
                                :sign-releases? true
                                :pom-file (b/pom-path {:lib lib :class-dir (str (:target opts "target") "/classes")})
                                :artifact jar-file})))