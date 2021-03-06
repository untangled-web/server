(ns untangled.server.impl.components.config-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.server.impl.components.config :as cfg]
            [untangled.server.core :refer [new-config raw-config]]
            [untangled-spec.core :refer
             [specification component behavior assertions when-mocking provided]]
            [clojure.test :as t]
            [taoensso.timbre :as timbre])
  (:import (java.io File)
           (clojure.lang ExceptionInfo)))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["untangled.server.impl.components.config"]}
           (%)))

(defn with-tmp-edn-file
  "Creates a temporary edn file with stringified `contents`,
   calls `f` with its absolute path,
   and returns the result after deleting the file."
  [contents f]
  (let [tmp-file (File/createTempFile "tmp-file" ".edn")
        _ (spit tmp-file (str contents))
        abs-path (.getAbsolutePath tmp-file)
        res (f abs-path)]
    (.delete tmp-file) res))

(def defaults-path "config/defaults.edn")

(specification "untangled.config"
  (when-mocking
    (cfg/get-defaults defaults-path) => {}
    (cfg/get-system-prop "config") => "some-file"
    (cfg/get-config "some-file") => {:k :v}

    (behavior "looks for system property -Dconfig"
      (assertions
        (cfg/load-config {}) => {:k :v})))

  (behavior "does not fail when returning nil"
    (assertions
      (#'cfg/get-system-prop "config") => nil))
  (behavior "defaults file is always used to provide missing values"
    (when-mocking
      (cfg/get-defaults defaults-path) => {:a :b}
      (cfg/get-config nil) => {:c :d}
      (assertions
        (cfg/load-config {}) => {:a :b
                                 :c :d})))

  (behavior "config file overrides defaults"
    (when-mocking
      (cfg/get-defaults defaults-path) => {:a {:b {:c :d}
                                               :e {:z :v}}}
      (cfg/get-config nil) => {:a {:b {:c :f
                                       :u :y}
                                   :e 13}}
      (assertions (cfg/load-config {}) => {:a {:b {:c :f
                                                   :u :y}
                                               :e 13}})))

  (component "load-config"
    (behavior "crashes if no default is found"
      (assertions
        (cfg/load-config {}) =throws=> (ExceptionInfo #"")))
    (behavior "crashes if no config is found"
      (when-mocking
        (cfg/get-defaults defaults-path) => {}
        (assertions (cfg/load-config {}) =throws=> (ExceptionInfo #""))))
    (behavior "falls back to `config-path`"
      (when-mocking
        (cfg/get-defaults defaults-path) => {}
        (cfg/get-config "/some/path") => {:k :v}
        (assertions (cfg/load-config {:config-path "/some/path"}) => {:k :v})))
    (behavior "recursively resolves symbols using resolve-symbol"
      (when-mocking
        (cfg/get-defaults defaults-path) => {:a {:b {:c 'clojure.core/symbol}}
                                             :v [0 "d"]
                                             :s #{'clojure.core/symbol}}
        (cfg/get-config nil) => {}
        (assertions (cfg/load-config {}) => {:a {:b {:c #'clojure.core/symbol}}
                                             :v [0 "d"]
                                             :s #{#'clojure.core/symbol}})))
    (behavior "passes config-path to get-config"
      (when-mocking
        (cfg/get-defaults defaults-path) => {}
        (cfg/get-config "/foo/bar") => {}
        (assertions (cfg/load-config {:config-path "/foo/bar"}) => {})))
    (assertions
      "config-path can be a relative path"
      (cfg/load-config {:config-path "not/abs/path"})
      =throws=> (ExceptionInfo #"Invalid config file")

      "prints the invalid path in the exception message"
      (cfg/load-config {:config-path "invalid/file"})
      =throws=> (ExceptionInfo #"invalid/file")))

  (component "resolve-symbol"
    (behavior "requires if necessary"
      (when-mocking
        (resolve 'untangled.server.fixtures.dont-require-me/stahp) => false
        (require 'untangled.server.fixtures.dont-require-me) => true
        (assertions (#'cfg/resolve-symbol 'untangled.server.fixtures.dont-require-me/stahp) => false)))
    (behavior "fails if require fails"
      (assertions
        (#'cfg/resolve-symbol 'srsly/not-a-var) =throws=> (java.io.FileNotFoundException #"")))
    (behavior "if not found in the namespace after requiring"
      (assertions
        (#'cfg/resolve-symbol 'untangled.server.fixtures.dont-require-me/invalid) =throws=> (AssertionError #"not \(nil")))
    (behavior "must be namespaced, throws otherwise"
      (assertions
        (#'cfg/resolve-symbol 'invalid) =throws=> (AssertionError #"namespace"))))

  (component "load-edn"
    (behavior "returns nil if absolute file is not found"
      (assertions (#'cfg/load-edn "/garbage") => nil))
    (behavior "returns nil if relative file is not on classpath"
      (assertions (#'cfg/load-edn "garbage") => nil))
    (behavior "can load edn from the classpath"
      (assertions (:some-key (#'cfg/load-edn "resources/config/defaults.edn")) => :some-default-val))
    (behavior :integration "can load edn from the disk"
      (assertions (with-tmp-edn-file {:foo :bar} #'cfg/load-edn) => {:foo :bar}))
    (behavior :integration "can load edn with symbols"
      (assertions (with-tmp-edn-file {:sym 'sym} #'cfg/load-edn) => {:sym 'sym}))
    (behavior "can load edn with :env/vars"
      (when-mocking
        (cfg/get-system-env "FAKE_ENV_VAR") => "FAKE STUFF"
        (cfg/get-defaults defaults-path) => {}
        (cfg/get-system-prop "config") => :..cfg-path..
        (cfg/get-config :..cfg-path..) => {:fake :env/FAKE_ENV_VAR}
        (assertions (cfg/load-config) => {:fake "FAKE STUFF"}))
      (behavior "when the namespace is env.edn it will edn/read-string it"
        (when-mocking
          (cfg/get-system-env "FAKE_ENV_VAR") => "3000"
          (cfg/get-defaults defaults-path) => {}
          (cfg/get-system-prop "config") => :..cfg-path..
          (cfg/get-config :..cfg-path..) => {:fake :env.edn/FAKE_ENV_VAR}
          (assertions (cfg/load-config) => {:fake 3000}))
        (behavior "buyer beware as it'll parse it in ways you might not expect!"
          (when-mocking
            (cfg/get-system-env "FAKE_ENV_VAR") => "http://google.com"
            (cfg/get-defaults defaults-path) => {}
            (cfg/get-system-prop "config") => :..cfg-path..
            (cfg/get-config :..cfg-path..) => {:fake :env.edn/FAKE_ENV_VAR}
            (assertions (cfg/load-config) => {:fake 'http://google.com}))))))

  (component "open-config-file"
    (behavior "takes in a path, finds the file at that path and should return a clojure map"
      (when-mocking
        (cfg/load-edn "/foobar") => "42"
        (assertions
          (#'cfg/open-config-file "/foobar") => "42")))
    (behavior "or if path is nil, uses a default path"
      (assertions
        (#'cfg/open-config-file nil) =throws=> (ExceptionInfo #"Invalid config file")))
    (behavior "if path doesn't exist on fs, it throws an ex-info"
      (assertions
        (#'cfg/get-config "/should/fail") =throws=> (ExceptionInfo #"Invalid config file")))))

(defrecord App []
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn new-app []
  (component/using
    (map->App {})
    [:config]))

(specification "untangled.server.impl.components.config"
  (component "new-config"
    (behavior "returns a stuartsierra component"
      (assertions (satisfies? component/Lifecycle (new-config "w/e")) => true)
      (behavior ".start loads the config"
        (when-mocking
          (cfg/load-config _) => "42"
          (assertions (:value (.start (new-config "mocked-out"))) => "42")))
      (behavior ".stop removes the config"
        (when-mocking
          (cfg/load-config _) => "wateva"
          (assertions (-> (new-config "mocked-out") .start .stop :config) => nil)))))

  (behavior "new-config can be injected through a system-map"
    (when-mocking
      (cfg/load-config _) => {:foo :bar}
      (assertions
        (-> (component/system-map
              :config (new-config "mocked-out")
              :app (new-app)) .start :app :config :value) => {:foo :bar})))

  (behavior "raw-config creates a config with the passed value"
    (assertions (-> (component/system-map
                      :config (raw-config {:some :config})
                      :app (new-app))
                  .start :app :config :value) => {:some :config})))
