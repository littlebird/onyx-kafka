(ns onyx.plugin.input-test
  (:require [clojure.core.async :refer [chan >!! <!!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [info error] :as timbre]
            [onyx.plugin.kafka]
            [clj-kafka.producer :as kp]
            [clj-kafka.admin :as kadmin]
            [onyx.kafka.embedded-server :as ke]
            [onyx.api]
            [midje.sweet :refer :all]))

(def id (java.util.UUID/randomUUID))

(def zk-addr "127.0.0.1:2188")

(def env-config
  {:zookeeper/address zk-addr
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/id id})

(def peer-config
  {:zookeeper/address zk-addr
   :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40199
   :onyx.messaging/bind-addr "localhost"
   :onyx/id id})

(def env (onyx.api/start-env env-config))

(def kafka-server
  (component/start 
    (ke/map->EmbeddedKafka {:hostname "127.0.0.1" 
                            :port 9092
                            :broker-id 0
                            :log-dir (str "/tmp/embedded-kafka" (java.util.UUID/randomUUID))
                            :zookeeper-addr zk-addr})))

(def peer-group (onyx.api/start-peer-group peer-config))

(def topic (str "onyx-test-" (java.util.UUID/randomUUID)))


(with-open [zk (kadmin/zk-client zk-addr)]
  (kadmin/create-topic zk topic
                      {:partitions 2}))


(def producer
  (kp/producer
   {"metadata.broker.list" "127.0.0.1:9092"
    "serializer.class" "kafka.serializer.DefaultEncoder"
    "partitioner.class" "kafka.producer.DefaultPartitioner"}))

(kp/send-message producer (kp/message topic (.getBytes (pr-str {:n 1}))))
(kp/send-message producer (kp/message topic (.getBytes (pr-str {:n 2}))))
(kp/send-message producer (kp/message topic (.getBytes (pr-str {:n 3}))))


(def producer2
  (kp/producer
   {"metadata.broker.list" "127.0.0.1:9092"
    "serializer.class" "kafka.serializer.DefaultEncoder"
    "partitioner.class" "kafka.producer.DefaultPartitioner"}))

(kp/send-message producer2 (kp/message topic (.getBytes (pr-str {:n 4}))))
(kp/send-message producer2 (kp/message topic (.getBytes (pr-str {:n 5}))))
(kp/send-message producer2 (kp/message topic (.getBytes (pr-str {:n 6}))))

(defn deserialize-message [bytes]
  (read-string (String. bytes "UTF-8")))

(def workflow
  [[:read-messages :identity]
   [:identity :out]])

(def catalog
  [{:onyx/name :read-messages
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :onyx/type :input
    :onyx/medium :kafka
    :kafka/topic topic
    :kafka/group-id "onyx-consumer"
    :kafka/fetch-size 307200
    :kafka/chan-capacity 1000
    :kafka/zookeeper zk-addr
    :kafka/offset-reset :smallest
    :kafka/force-reset? true
    :kafka/empty-read-back-off 500
    :kafka/commit-interval 500
    :kafka/deserializer-fn :onyx.plugin.input-test/deserialize-message
    :onyx/min-peers 2
    :onyx/max-peers 2
    :onyx/batch-size 100
    :onyx/doc "Reads messages from a Kafka topic"}

   {:onyx/name :identity
    :onyx/fn :clojure.core/identity
    :onyx/type :function
    :onyx/batch-size 100}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 100
    :onyx/doc "Writes segments to a core.async channel"}])

(def out-chan (chan 100))

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :read-messages
    :lifecycle/calls :onyx.plugin.kafka/read-messages-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.input-test/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def v-peers (onyx.api/start-peers 4 peer-group))

(onyx.api/submit-job
 peer-config
 {:catalog catalog :workflow workflow
  :lifecycles lifecycles
  :task-scheduler :onyx.task-scheduler/balanced})

;; remove me after learning how to handle the sentinel
(Thread/sleep 10000)

;; do not send done, as it is not supported for multiple partitions

(def results (doall (map (fn [_] (<!! out-chan)) (range 6))))

(fact (sort-by :n results) 
      => [{:n 1} {:n 2} {:n 3} 
          {:n 4} {:n 5} {:n 6}])

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)

(component/stop kafka-server)
