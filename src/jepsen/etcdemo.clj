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
             [independent :as independent]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]]
            [knossos.model :as model]
            [jepsen.control.util :as cu]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [jepsen.etcdemo.support :as s]))

(defn r   [test process] {:type :invoke, :f :read, :value nil})
(defn w   [test process] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [test process] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})


; just to make things a little prettier when reading logs
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
         (assoc this :conn (v/connect (s/client-url node)
                                      {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
           (let [[k v] (:value op)]
             (try+
               (case (:f op)
                 :read (let [v (parse-long (v/get conn k {:quorum? (:quorum test)}))]
                          (assoc op :type :ok
                                 :value (independent/tuple k v)))
                 :write (do (v/reset! conn k v)
                          (assoc op :type, :ok))
                 :cas (let [[v v'] v]
                          (assoc op :type (if (v/cas! conn k v v')
                            :ok
                            :fail))))

              (catch java.net.SocketTimeoutException e
                (assoc op
                       :type (if (= (:f  op) :read) :fail :info)
                      :error :timeout))

              (catch [:errorCode 100] ex
                (assoc op :type :fail, :error :not-found)))))

  (teardown! [_ test])

  (close! [_ test]))
  ; close connections here if the db library uses connections

(defn etcd-test
    "Given an options map from the command line runner (e.g. :nodes, :ssh,
    :concurrency, ...), constructs a test map."
    [opts]
    (let [quorum (boolean (:quorum opts))]
      (merge tests/noop-test
           opts
           {:os debian/os
            :db (s/db "v3.1.5")
            :name (str "etcd q=" quorum)
            :quorum quorum
            :client (Client. nil)
            :nemesis (nemesis/partition-random-halves)
            :generator (->> (independent/concurrent-generator
                             10
                             (range)
                             (fn [k]
                               (->>
                                 (gen/mix [r, w, cas])
                                (gen/stagger (/ (:rate opts)))
                                  (gen/limit (:ops-per-key opts)))))
                            (gen/nemesis
                             (gen/seq (cycle [(gen/sleep 5)
                                       {:type :info, :f :start}
                                       ; look at latency diagrams - start small eg 1 and increase
                                       ; need to allow for tcp connections to drop too
                                       (gen/sleep 5)
                                       {:type :info, :f :stop}])))
                            (gen/time-limit (:time-limit opts)))
            :checker (checker/compose
                      {:perf (checker/perf)
                       :per-key (independent/checker
                                 (checker/compose
                                  {:linear (checker/linearizable)
                                   :timeline (timeline/html)}))})
            :model (model/cas-register)})))

; (gen/nemesis nil) disables the nemesis")

(def cli-opts
  "Additional command line options"
  [["-q" "--quorum" "Use quorum reads"]
   ["-r" "--rate HZ" "approximate number of requests per second, per thread"
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %))
                 "Must be positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of operations per key"
    :default 100
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]])

(defn -main
    "Handles command line arguments. Can either run a test, or a web server for
    browsing results."
    [& args]
    (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test
                                           :opt-spec cli-opts})
                     (cli/serve-cmd))
              args))
