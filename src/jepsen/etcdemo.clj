(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [control :as c]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(defn db
  "Etcd at a particular version"
  [version]
  (reify db/DB
         (setup! [db test node]
                 (info :setting-up node))
         (teardown! [db test node]
                    (info :tearing-down node))))

(defn etcd-test
    "Given an options map from the command line runner (e.g. :nodes, :ssh,
    :concurrency, ...), constructs a test map."
    [opts]
    (merge tests/noop-test
           opts
           {:os debian/os
            :db (db "v3.1.5")}))

(defn -main
    "Handles command line arguments. Can either run a test, or a web server for
    browsing results."
    [& args]
    (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                     (cli/serve-cmd))
              args))
