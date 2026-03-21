(ns io.modelcontext.clojure-sdk.client-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.server :as server]
            [lsp4clj.server :as lsp.server]))

;;; Helpers

(defn create-connected-pair
  "Create a server and client connected through channels.
   Returns {:server server-endpoint, :client client-endpoint,
            :server-context context, :client-context context}"
  [server-spec client-opts]
  (let [;; Server -> Client channel
        s->c (async/chan 3)
        ;; Client -> Server channel
        c->s (async/chan 3)
        ;; Server reads from c->s, writes to s->c
        server-endpoint (lsp.server/chan-server {:input-ch c->s, :output-ch s->c})
        ;; Client reads from s->c, writes to c->s
        client-endpoint (lsp.server/chan-server {:input-ch s->c, :output-ch c->s})
        server-context (server/create-context! server-spec)
        client-context (client/create-context client-opts)]
    ;; Store protocol references for bidirectional communication
    (reset! (:protocol server-context) server-endpoint)
    {:server server-endpoint,
     :client client-endpoint,
     :server-context server-context,
     :client-context client-context}))

(defn start-pair!
  [{:keys [server client server-context client-context]}]
  (server/start! server server-context)
  (client/start! client client-context)
  nil)

(defn shutdown-pair!
  [{:keys [server client]}]
  (lsp.server/shutdown client)
  (lsp.server/shutdown server))

;;; Test data

(def echo-tool
  {:name "echo",
   :description "Echo input",
   :inputSchema {:type "object",
                 :properties {"message" {:type "string"}},
                 :required ["message"]},
   :handler (fn [{:keys [message]}] {:type "text", :text message})})

(def greet-tool
  {:name "greet",
   :description "Greet someone",
   :inputSchema {:type "object", :properties {"name" {:type "string"}}},
   :handler (fn [{:keys [name]}]
              {:type "text", :text (str "Hello, " name "!")})})

(def test-resource
  {:description "A test file",
   :mimeType "text/plain",
   :name "Test File",
   :uri "file:///test.txt",
   :handler
   (fn read-resource [uri]
     {:uri uri, :mimeType "text/plain", :text "Hello from Test File"})})

(def test-prompt
  {:name "analyze-code",
   :description "Analyze code",
   :arguments [{:name "code", :description "The code", :required true}],
   :handler (fn [args]
              {:messages [{:role "assistant",
                           :content {:type "text",
                                     :text (str "Analysis: " (:code args))}}]})})

;;; Tests

(deftest client-initialization
  (testing "Client can initialize connection with server"
    (let [pair (create-connected-pair
                 {:name "test-server", :version "1.0.0", :tools [echo-tool]}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :roots [{:uri "file:///home/user/project",
                           :name "My Project"}]})]
      (start-pair! pair)
      (let [result (client/initialize! (:client pair))]
        (is (some? result))
        (is (= "test-server" (get-in result [:serverInfo :name])))
        (is (= "1.0.0" (get-in result [:serverInfo :version])))
        (is (contains? result :capabilities))
        (is (contains? result :protocolVersion)))
      (shutdown-pair! pair))))

(deftest client-list-tools
  (testing "Client can list tools from server"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [echo-tool greet-tool]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/list-tools! (:client pair))]
        (is (= 2 (count (:tools result))))
        (is (= #{"echo" "greet"}
               (set (map :name (:tools result))))))
      (shutdown-pair! pair))))

(deftest client-call-tool
  (testing "Client can call a tool on the server"
    (let [pair (create-connected-pair
                 {:name "test-server", :version "1.0.0", :tools [echo-tool]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/call-tool! (:client pair)
                                      "echo"
                                      {:message "hello world"})]
        (is (some? (:content result)))
        (is (= "hello world" (-> result :content first :text))))
      (shutdown-pair! pair))))

(deftest client-list-resources
  (testing "Client can list resources from server"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [],
                  :resources [test-resource]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/list-resources! (:client pair))]
        (is (= 1 (count (:resources result))))
        (is (= "file:///test.txt" (:uri (first (:resources result))))))
      (shutdown-pair! pair))))

(deftest client-read-resource
  (testing "Client can read a resource from server"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [],
                  :resources [test-resource]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/read-resource! (:client pair) "file:///test.txt")]
        (is (= 1 (count (:contents result))))
        (is (= "Hello from Test File" (-> result :contents first :text))))
      (shutdown-pair! pair))))

(deftest client-list-prompts
  (testing "Client can list prompts from server"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [],
                  :prompts [test-prompt]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/list-prompts! (:client pair))]
        (is (= 1 (count (:prompts result))))
        (is (= "analyze-code" (:name (first (:prompts result))))))
      (shutdown-pair! pair))))

(deftest client-get-prompt
  (testing "Client can get a specific prompt from server"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [],
                  :prompts [test-prompt]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/get-prompt! (:client pair)
                                       "analyze-code"
                                       {:code "(defn foo [])"})]
        (is (some? (:messages result)))
        (is (= "Analysis: (defn foo [])"
               (-> result :messages first :content :text))))
      (shutdown-pair! pair))))

(deftest server-notifications-to-client
  (testing "Server can send notifications that the client receives"
    (let [pair (create-connected-pair
                 {:name "test-server",
                  :version "1.0.0",
                  :tools [echo-tool],
                  :resources [test-resource],
                  :prompts [test-prompt]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (testing "Server sends tool list changed notification"
        ;; This should not throw or cause issues
        (server/notify-tool-list-changed! (:server pair))
        ;; Give async processing a moment
        (Thread/sleep 50)
        (is true "Tool list changed notification sent without error"))
      (testing "Server sends resource list changed notification"
        (server/notify-resource-list-changed! (:server pair))
        (Thread/sleep 50)
        (is true "Resource list changed notification sent without error"))
      (testing "Server sends resource updated notification"
        (server/notify-resource-updated! (:server pair) "file:///test.txt")
        (Thread/sleep 50)
        (is true "Resource updated notification sent without error"))
      (testing "Server sends prompt list changed notification"
        (server/notify-prompt-list-changed! (:server pair))
        (Thread/sleep 50)
        (is true "Prompt list changed notification sent without error"))
      (testing "Server sends log message notification"
        (server/notify-log-message! (:server pair) "info" "Test log message"
                                    :logger "test-logger")
        (Thread/sleep 50)
        (is true "Log message notification sent without error"))
      (shutdown-pair! pair))))

(deftest dynamic-tool-registration
  (testing "Server can dynamically add tools and client sees them"
    (let [pair (create-connected-pair
                 {:name "test-server", :version "1.0.0", :tools [echo-tool]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (testing "Initially one tool"
        (let [result (client/list-tools! (:client pair))]
          (is (= 1 (count (:tools result))))
          (is (= "echo" (:name (first (:tools result)))))))
      (testing "Register a new tool dynamically"
        (server/register-tool! (:server-context pair)
                               {:name "greet",
                                :description "Greet someone",
                                :inputSchema {:type "object",
                                              :properties {"name"
                                                           {:type "string"}}}}
                               (fn [{:keys [name]}]
                                 {:type "text",
                                  :text (str "Hello, " name "!")}))
        ;; Notify client
        (server/notify-tool-list-changed! (:server pair)))
      (testing "Now two tools"
        (let [result (client/list-tools! (:client pair))]
          (is (= 2 (count (:tools result))))
          (is (= #{"echo" "greet"}
                 (set (map :name (:tools result)))))))
      (testing "New tool is callable"
        (let [result (client/call-tool! (:client pair)
                                        "greet"
                                        {:name "World"})]
          (is (= "Hello, World!" (-> result :content first :text)))))
      (shutdown-pair! pair))))

(deftest dynamic-capability-updates
  (testing "Registering tools/resources/prompts dynamically updates capabilities"
    (let [context (server/create-context! {:name "test", :version "1.0.0"})]
      (testing "Empty server has no capabilities"
        (is (= {} @(:capabilities context))))
      (testing "Adding a tool adds :tools capability"
        (server/register-tool! context
                               {:name "test-tool",
                                :description "Test",
                                :inputSchema {:type "object"}}
                               identity)
        (is (contains? @(:capabilities context) :tools)))
      (testing "Adding a resource adds :resources capability"
        (server/register-resource! context
                                   {:uri "file:///test", :name "Test"}
                                   identity)
        (is (contains? @(:capabilities context) :resources)))
      (testing "Adding a prompt adds :prompts capability"
        (server/register-prompt! context {:name "test-prompt"} identity)
        (is (contains? @(:capabilities context) :prompts))))))

(deftest server-capability-negotiation
  (testing "Server only advertises capabilities it supports"
    (testing "Server with tools only"
      (let [pair (create-connected-pair
                   {:name "test-server", :version "1.0.0", :tools [echo-tool]}
                   {:client-info {:name "test-client", :version "1.0.0"}})]
        (start-pair! pair)
        (let [result (client/initialize! (:client pair))
              caps (:capabilities result)]
          (is (contains? caps :tools))
          (is (not (contains? caps :resources)))
          (is (not (contains? caps :prompts))))
        (shutdown-pair! pair)))
    (testing "Server with all capabilities"
      (let [pair (create-connected-pair
                   {:name "test-server",
                    :version "1.0.0",
                    :tools [echo-tool],
                    :resources [test-resource],
                    :prompts [test-prompt]}
                   {:client-info {:name "test-client", :version "1.0.0"}})]
        (start-pair! pair)
        (let [result (client/initialize! (:client pair))
              caps (:capabilities result)]
          (is (contains? caps :tools))
          (is (contains? caps :resources))
          (is (contains? caps :prompts)))
        (shutdown-pair! pair)))
    (testing "Empty server"
      (let [pair (create-connected-pair
                   {:name "test-server", :version "1.0.0"}
                   {:client-info {:name "test-client", :version "1.0.0"}})]
        (start-pair! pair)
        (let [result (client/initialize! (:client pair))
              caps (:capabilities result)]
          (is (not (contains? caps :tools)))
          (is (not (contains? caps :resources)))
          (is (not (contains? caps :prompts))))
        (shutdown-pair! pair)))))

(deftest client-ping
  (testing "Client can ping the server"
    (let [pair (create-connected-pair
                 {:name "test-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/ping! (:client pair))]
        (is (= {} result)))
      (shutdown-pair! pair))))
