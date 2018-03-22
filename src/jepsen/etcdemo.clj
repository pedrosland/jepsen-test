(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as v]
            [slingshot.slingshot :refer [try+]]
            [jepsen [cli :as cli]
             [checker :as checker]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]]
            [knossos.model :as model]
            [jepsen.control.util :as cu]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 2379))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn db
  "Etcd at a particular version"
  [version]
  (reify db/DB
         (setup! [db test node]
                (let [url (str "https://storage.googleapis.com/etcd/" version
                               "/etcd-" version "-linux-amd64.tar.gz")]
                  (c/su
                    (cu/install-archive! url dir)
                    (cu/start-daemon!
                     {:logfile logfile
                      :pidfile pidfile
                      :chdir dir}
                     binary
                     :--log-output                   :stderr
                     :--name                         (name node)
                     :--listen-peer-urls             (peer-url   node)
                     :--listen-client-urls           (client-url node)
                     :--advertise-client-urls        (client-url node)
                     :--initial-cluster-state        :new
                     :--initial-advertise-peer-urls  (peer-url node)
                     :--initial-cluster              (initial-cluster test))))

                    ; this should loop and wait for the db to be alive but we're lazy

                    (Thread/sleep 10000))
         (teardown! [db test node]
                    (info node "tearing down etcd")
                    (cu/stop-daemon! binary pidfile)
                    (c/su (c/exec :rm :-rf dir)))

         db/LogFiles
         (log-files [db test node]
                    [logfile])))

(defn r   [test process] {:type :invoke, :f :read, :value nil})
(defn w   [test process] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [test process] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})


(defn parse-long
  "Parses a string as long"
  [x]
  (when x
    (Long/parseLong x)))

(defrecord Client [conn]
  client/Client

  ; in open!, should always use a timeout.
  ; can use this if not provided by client lib: (util/timeout 5000 :default (can write stuff here))
  (open! [this test node]
         (assoc this :conn (v/connect (client-url node)
                                      {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
           (case (:f op)
             :read (let [v (parse-long (v/get conn "foo"))]
                     (assoc op :type :ok, :value v))
             :write (do (v/reset! conn "foo" (:value op))
                      (assoc op :type, :ok))
             :cas (let [[v v'] (:value op)]
                    (try+
                      (assoc op :type (if (v/cas! conn "foo" v v')
                        :ok
                        :fail))
                      (catch [:errorCode 100] e
                        (assoc op :type :fail, :error :not-found))))))

  (teardown! [_ test])

  (close! [_ test]))
  ; close connections here if the db library uses connections

(defn etcd-test
    "Given an options map from the command line runner (e.g. :nodes, :ssh,
    :concurrency, ...), constructs a test map."
    [opts]
    (merge tests/noop-test
           opts
           {:os debian/os
            :db (db "v3.1.5")
            :client (Client. nil)
            :nemesis (nemesis/partition-random-halves)
            :generator (->> (gen/mix [r, w, cas])
                            (gen/stagger 1)
                            (gen/nemesis
                             (gen/seq (cycle [(gen/sleep 5)
                                       {:type :info, :f :start}
                                       ; look at latency diagrams - start small eg 1 and increase
                                       ; need to allow for tcp connections to drop too
                                       (gen/sleep 5)
                                       {:type :info, :f :stop}])))
                            (gen/time-limit 40))
            :checker (checker/compose
                      {:linear (checker/linearizable)
                       :perf (checker/perf)
                       :timeline (timeline/html)})
            :model (model/cas-register)}))

; (gen/nemesis nil) disables the nemesis")

(defn -main
    "Handles command line arguments. Can either run a test, or a web server for
    browsing results."
    [& args]
    (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                     (cli/serve-cmd))
              args))
