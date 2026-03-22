(ns io.modelcontext.clojure-sdk.integration-test
  "Integration tests that verify end-to-end client-server communication
   using piped streams to simulate stdio transport."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [io.modelcontext.clojure-sdk.server :as server]
            [lsp4clj.server :as lsp.server])
  (:import (java.io PipedInputStream PipedOutputStream)))

;;; Test helpers

(defn create-piped-pair
  "Create a server and client connected through piped streams,
   simulating stdio transport without an actual subprocess.
   Returns {:server endpoint, :client endpoint, :server-context ctx, :client-context ctx}"
  [server-spec client-opts]
  ;; Server writes to server-out, client reads from server-out
  ;; Client writes to client-out, server reads from client-out
  (let [server-out-pipe (PipedOutputStream.)
        server-in-pipe (PipedInputStream.)
        client-reads-from (PipedInputStream. server-out-pipe)
        server-reads-from (PipedOutputStream. server-in-pipe)
        ;; Server endpoint: reads from server-in-pipe, writes to server-out-pipe
        server-input-ch (mcp.io-chan/input-stream->input-chan server-in-pipe)
        server-output-ch (mcp.io-chan/output-stream->output-chan server-out-pipe)
        server-endpoint (lsp.server/chan-server {:input-ch server-input-ch,
                                                 :output-ch server-output-ch})
        ;; Client endpoint: reads from client-reads-from, writes to server-reads-from
        client-input-ch (mcp.io-chan/input-stream->input-chan client-reads-from)
        client-output-ch (mcp.io-chan/output-stream->output-chan server-reads-from)
        client-endpoint (lsp.server/chan-server {:input-ch client-input-ch,
                                                 :output-ch client-output-ch})
        server-context (server/create-context! server-spec)
        client-context (client/create-context client-opts)]
    (reset! (:protocol server-context) server-endpoint)
    {:server server-endpoint,
     :client client-endpoint,
     :server-context server-context,
     :client-context client-context}))

(defn start-pair!
  [{:keys [server client server-context client-context]}]
  (server/start! server server-context)
  (client/start! client client-context))

(defn shutdown-pair!
  [{:keys [server client]}]
  (client/shutdown! client)
  (server/shutdown! server))

;;; Test tools and resources

(def calc-add
  {:name "add",
   :description "Add two numbers",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number"}, "b" {:type "number"}},
                 :required ["a" "b"]},
   :handler (fn [{:keys [a b]}] {:type "text", :text (str (+ a b))})})

(def calc-multiply
  {:name "multiply",
   :description "Multiply two numbers",
   :inputSchema {:type "object",
                 :properties {"a" {:type "number"}, "b" {:type "number"}},
                 :required ["a" "b"]},
   :handler (fn [{:keys [a b]}] {:type "text", :text (str (* a b))})})

(def readme-resource
  {:description "Project README",
   :mimeType "text/plain",
   :name "README",
   :uri "file:///README.md",
   :handler
   (fn [uri] {:uri uri, :mimeType "text/plain", :text "# My Project\nHello!"})})

(def greeting-prompt
  {:name "greet",
   :description "Generate a greeting",
   :arguments [{:name "name", :description "Name to greet", :required true}],
   :handler (fn [args]
              {:messages [{:role "assistant",
                           :content {:type "text",
                                     :text (str "Hello, " (:name args) "!")}}]})})

;;; Integration Tests

(deftest integration-full-lifecycle
  (testing "Full client-server lifecycle over piped streams"
    (let [pair (create-piped-pair
                 {:name "calc-server",
                  :version "2.0.0",
                  :tools [calc-add calc-multiply],
                  :resources [readme-resource],
                  :prompts [greeting-prompt]}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :roots [{:uri "file:///home/user/project",
                           :name "My Project"}]})]
      (start-pair! pair)

      (testing "1. Initialize connection"
        (let [result (client/initialize! (:client pair))]
          (is (= "calc-server" (get-in result [:serverInfo :name])))
          (is (= "2.0.0" (get-in result [:serverInfo :version])))
          (is (contains? (:capabilities result) :tools))))

      (testing "2. Ping"
        (is (= {} (client/ping! (:client pair)))))

      (testing "3. List and call tools"
        (let [tools-result (client/list-tools! (:client pair))]
          (is (= 2 (count (:tools tools-result))))
          (is (= #{"add" "multiply"}
                 (set (map :name (:tools tools-result))))))
        (let [add-result (client/call-tool! (:client pair) "add" {:a 3, :b 4})]
          (is (= "7" (-> add-result :content first :text))))
        (let [mul-result (client/call-tool! (:client pair)
                                            "multiply"
                                            {:a 5, :b 6})]
          (is (= "30" (-> mul-result :content first :text)))))

      (testing "4. List and read resources"
        (let [resources-result (client/list-resources! (:client pair))]
          (is (= 1 (count (:resources resources-result))))
          (is (= "file:///README.md"
                 (:uri (first (:resources resources-result))))))
        (let [read-result (client/read-resource! (:client pair)
                                                 "file:///README.md")]
          (is (= "# My Project\nHello!"
                 (-> read-result :contents first :text)))))

      (testing "5. List and get prompts"
        (let [prompts-result (client/list-prompts! (:client pair))]
          (is (= 1 (count (:prompts prompts-result))))
          (is (= "greet" (:name (first (:prompts prompts-result))))))
        (let [prompt-result (client/get-prompt! (:client pair)
                                                "greet"
                                                {:name "World"})]
          (is (= "Hello, World!"
                 (-> prompt-result :messages first :content :text)))))

      (testing "6. Server can request roots from client"
        (let [roots (server/request-roots-list! (:server pair))]
          (is (= [{:uri "file:///home/user/project", :name "My Project"}]
                 roots))))

      (shutdown-pair! pair))))

(deftest integration-sampling
  (testing "Server can request LLM sampling from client over piped streams"
    (let [pair (create-piped-pair
                 {:name "sampling-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :sampling-handler
                  (fn [params]
                    {:role "assistant",
                     :content {:type "text",
                               :text (str "Sampled: "
                                          (-> params :messages first :content
                                              :text))},
                     :model "test-model",
                     :stopReason "endTurn"})})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Server requests sampling and gets response"
        (let [result (server/request-sampling!
                       (:server pair)
                       {:messages [{:role "user",
                                    :content {:type "text",
                                              :text "What is 2+2?"}}],
                        :maxTokens 100})]
          (is (some? result))
          (is (= "test-model" (:model result)))
          (is (= "Sampled: What is 2+2?"
                 (get-in result [:content :text])))))

      (shutdown-pair! pair))))

(deftest integration-dynamic-registration
  (testing "Dynamically register and deregister tools at runtime"
    (let [pair (create-piped-pair
                 {:name "dynamic-server", :version "1.0.0", :tools [calc-add]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Initially has one tool"
        (let [result (client/list-tools! (:client pair))]
          (is (= 1 (count (:tools result))))
          (is (= "add" (:name (first (:tools result)))))))

      (testing "Register a new tool at runtime"
        (server/register-tool!
          (:server-context pair)
          {:name "subtract",
           :description "Subtract two numbers",
           :inputSchema {:type "object",
                         :properties {"a" {:type "number"}, "b" {:type "number"}}}}
          (fn [{:keys [a b]}] {:type "text", :text (str (- a b))}))
        (let [result (client/list-tools! (:client pair))]
          (is (= 2 (count (:tools result))))))

      (testing "Call the dynamically registered tool"
        (let [result (client/call-tool! (:client pair) "subtract" {:a 10, :b 3})]
          (is (= "7" (-> result :content first :text)))))

      (testing "Deregister a tool at runtime"
        (server/deregister-tool! (:server-context pair) "subtract")
        (let [result (client/list-tools! (:client pair))]
          (is (= 1 (count (:tools result))))
          (is (= "add" (:name (first (:tools result)))))))

      (shutdown-pair! pair))))

(deftest integration-logging-level
  (testing "Client can set server logging level over piped streams"
    (let [pair (create-piped-pair
                 {:name "log-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Set logging level"
        (let [result (client/set-logging-level! (:client pair) "warning")]
          (is (= {} result))))

      (testing "Level is stored in server context"
        (is (= "warning" @(:log-level (:server-context pair)))))

      (shutdown-pair! pair))))

(deftest integration-completions
  (testing "Client can request completions from server over piped streams"
    (let [pair (create-piped-pair
                 {:name "completion-server", :version "1.0.0",
                  :tools [],
                  :prompts [{:name "greet",
                             :description "Greet someone",
                             :arguments [{:name "name", :required true}],
                             :handler (fn [args]
                                        {:messages
                                         [{:role "assistant",
                                           :content {:type "text",
                                                     :text (str "Hi " (:name args))}}]})}]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (server/register-completion!
        (:server-context pair) "greet" "name"
        (fn [partial]
          {:values (filterv #(clojure.string/starts-with? % partial)
                            ["Alice" "Bob" "Charlie"])}))

      (testing "Returns matching completions"
        (let [result (client/complete!
                       (:client pair)
                       {:type "ref/prompt", :name "greet"}
                       {:name "name", :value "A"})]
          (is (= ["Alice"] (get-in result [:completion :values])))))

      (testing "Returns empty for no matches"
        (let [result (client/complete!
                       (:client pair)
                       {:type "ref/prompt", :name "greet"}
                       {:name "name", :value "Z"})]
          (is (= [] (get-in result [:completion :values])))))

      (testing "complete-prompt-arg! convenience wrapper"
        (let [values (client/complete-prompt-arg!
                       (:client pair) "greet" "name" "B")]
          (is (= ["Bob"] values))))

      (shutdown-pair! pair))))

(deftest integration-resource-subscriptions
  (testing "Client can subscribe/unsubscribe to resource updates"
    (let [pair (create-piped-pair
                 {:name "sub-server", :version "1.0.0",
                  :tools [],
                  :resources [readme-resource]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Subscribe to a resource"
        (let [result (client/subscribe-resource! (:client pair)
                                                  "file:///README.md")]
          (is (= {} result))))

      (testing "Subscription is tracked in server context"
        (is (contains? @(:subscriptions (:server-context pair))
                       "file:///README.md")))

      (testing "Unsubscribe from a resource"
        (let [result (client/unsubscribe-resource! (:client pair)
                                                    "file:///README.md")]
          (is (= {} result))))

      (testing "Subscription is removed"
        (is (not (contains? @(:subscriptions (:server-context pair))
                            "file:///README.md"))))

      (shutdown-pair! pair))))

(deftest integration-version-negotiation
  (testing "Client and server negotiate protocol version over piped streams"
    (let [pair (create-piped-pair
                 {:name "version-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (testing "Known version is echoed back"
        (let [result (client/initialize!
                       (:client pair)
                       {:protocol-version "2024-11-05",
                        :client-info {:name "test-client", :version "1.0.0"},
                        :capabilities {}})]
          (is (= "2024-11-05" (:protocolVersion result)))))
      (shutdown-pair! pair))
    (let [pair (create-piped-pair
                 {:name "version-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (testing "Unknown version returns server's latest"
        (let [result (client/initialize!
                       (:client pair)
                       {:protocol-version "UNKNOWN-VERSION",
                        :client-info {:name "test-client", :version "1.0.0"},
                        :capabilities {}})]
          (is (= "2025-03-26" (:protocolVersion result)))))
      (shutdown-pair! pair))))

(deftest integration-error-handling
  (testing "Errors are properly propagated through piped streams"
    (let [pair (create-piped-pair
                 {:name "error-server",
                  :version "1.0.0",
                  :tools [{:name "failing-tool",
                           :description "A tool that throws",
                           :inputSchema {:type "object"},
                           :handler (fn [_]
                                      (throw (ex-info "Intentional error" {})))}]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Tool error is returned in response"
        (let [result (client/call-tool! (:client pair) "failing-tool" {})]
          (is (true? (:isError result)))
          (is (some? (:content result)))))

      (testing "Calling non-existent tool returns error"
        (let [result (client/call-tool! (:client pair) "non-existent" {})]
          (is (some? (:error result)))))
      (testing "Reading non-existent resource returns error"
        (let [result (client/read-resource! (:client pair)
                                             "file:///nope.txt")]
          (is (some? (:error result)))))
      (testing "Getting non-existent prompt returns error"
        (let [result (client/get-prompt! (:client pair) "nope" {})]
          (is (some? (:error result)))))

      (shutdown-pair! pair))))

(deftest integration-deregistered-tool-call
  (testing "Calling a deregistered tool returns error"
    (let [pair (create-piped-pair
                 {:name "dereg-server", :version "1.0.0", :tools [calc-add]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Tool works before deregistration"
        (let [result (client/call-tool! (:client pair) "add" {:a 1, :b 2})]
          (is (= "3" (-> result :content first :text)))))

      (server/deregister-tool! (:server-context pair) "add")

      (testing "Tool call returns error after deregistration"
        (let [result (client/call-tool! (:client pair) "add" {:a 1, :b 2})]
          (is (some? (:error result)))))

      (testing "Tool list is empty after deregistration"
        (let [result (client/list-tools! (:client pair))]
          (is (= 0 (count (:tools result))))))

      (shutdown-pair! pair))))

(deftest integration-resource-template-read
  (testing "Client can read resources via template-matched URIs"
    (let [pair (create-piped-pair
                 {:name "tmpl-server", :version "1.0.0",
                  :tools [],
                  :resource-templates
                  [{:uriTemplate "file:///users/{userId}/profile",
                    :name "User Profile",
                    :description "A user's profile",
                    :mimeType "text/plain",
                    :handler (fn [uri]
                               {:uri uri,
                                :mimeType "text/plain",
                                :text (str "Profile at " uri)})}]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Template appears in resource templates list"
        (let [result (client/list-resource-templates! (:client pair))]
          (is (= 1 (count (:resourceTemplates result))))
          (is (= "file:///users/{userId}/profile"
                 (:uriTemplate (first (:resourceTemplates result)))))))

      (testing "Read resource matching template pattern"
        (let [result (client/read-resource! (:client pair)
                                             "file:///users/42/profile")]
          (is (= "Profile at file:///users/42/profile"
                 (-> result :contents first :text)))))

      (shutdown-pair! pair))))

(deftest integration-pagination
  (testing "Client can paginate through tools over piped streams"
    (let [tools (for [i (range 5)]
                  {:name (str "tool-" i)
                   :description (str "Tool " i)
                   :inputSchema {:type "object"}
                   :handler (fn [_] {:type "text" :text (str i)})})
          pair (create-piped-pair
                 {:name "page-server", :version "1.0.0",
                  :page-size 2,
                  :tools (vec tools)}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))

      (testing "Collect all tools across pages"
        (let [all-tools (loop [cursor nil
                               acc []]
                          (let [result (client/list-tools! (:client pair)
                                                           (cond-> {}
                                                             cursor (assoc :cursor cursor)))
                                new-acc (into acc (:tools result))]
                            (if-let [next-cursor (:nextCursor result)]
                              (recur next-cursor new-acc)
                              new-acc)))]
          (is (= 5 (count all-tools))
              "Should collect all 5 tools across pages")
          (is (= (set (map #(str "tool-" %) (range 5)))
                 (set (map :name all-tools))))))

      (shutdown-pair! pair))))

(deftest integration-instructions
  (testing "Server instructions are included in initialize response"
    (let [pair (create-piped-pair
                 {:name "instructed-server", :version "1.0.0",
                  :tools [],
                  :instructions "Use this server for math operations only."}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (let [result (client/initialize! (:client pair))]
        (is (= "Use this server for math operations only."
               (:instructions result))))
      (shutdown-pair! pair)))
  (testing "Server without instructions omits field from response"
    (let [pair (create-piped-pair
                 {:name "plain-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (let [result (client/initialize! (:client pair))]
        (is (not (contains? result :instructions))))
      (shutdown-pair! pair))))

(deftest integration-initialize-stores-server-info
  (testing "Client stores server info and capabilities in context after initialize"
    (let [pair (create-piped-pair
                 {:name "info-server", :version "3.0.0",
                  :tools [calc-add]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair) {:context (:client-context pair)})
      (is (= "info-server"
             (:name @(:server-info (:client-context pair)))))
      (is (= "3.0.0"
             (:version @(:server-info (:client-context pair)))))
      (is (some? @(:server-capabilities (:client-context pair))))
      (shutdown-pair! pair))))

(deftest integration-call-tool-with-progress-token
  (testing "call-tool! with _meta progress token is received by handler"
    (let [captured-meta (atom nil)
          pair (create-piped-pair
                 {:name "progress-server", :version "1.0.0",
                  :tools [{:name "slow-tool"
                           :description "A tool that captures meta"
                           :inputSchema {:type "object"}
                           :handler (fn [_]
                                      (reset! captured-meta server/*request-meta*)
                                      {:type "text" :text "done"})}]}
                 {:client-info {:name "test-client", :version "1.0.0"}})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (let [result (client/call-tool! (:client pair) "slow-tool" {}
                                      {:progressToken "progress-42"})]
        (is (some? result))
        (is (= {:progressToken "progress-42"} @captured-meta)))
      (shutdown-pair! pair))))

(deftest integration-notification-callbacks
  (testing "Client receives tool list changed notification via callback"
    (let [notified (promise)
          pair (create-piped-pair
                 {:name "notif-server", :version "1.0.0",
                  :tools [calc-add]}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :on-tools-changed (fn [_] (deliver notified true))})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      ;; Register a new tool to trigger notification
      (server/register-tool! (:server-context pair)
                             {:name "new-tool"
                              :description "New"
                              :inputSchema {:type "object"}}
                             (fn [_] "ok"))
      (server/notify-tool-list-changed! @(:protocol (:server-context pair)))
      (is (= true (deref notified 5000 :timeout)))
      (shutdown-pair! pair)))

  (testing "Client receives log message notification via callback"
    (let [log-messages (atom [])
          pair (create-piped-pair
                 {:name "log-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :on-log-message (fn [msg] (swap! log-messages conj msg))})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      (server/notify-log-message! @(:protocol (:server-context pair))
                                  "info" "Test log entry")
      (Thread/sleep 500)
      (is (= 1 (count @log-messages)))
      (is (= "info" (:level (first @log-messages))))
      (shutdown-pair! pair))))

(deftest integration-update-roots
  (testing "Client can update roots and server receives the change"
    (let [pair (create-piped-pair
                 {:name "roots-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :roots [{:uri "file:///old" :name "Old Root"}]})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      ;; Verify initial roots
      (is (= [{:uri "file:///old" :name "Old Root"}]
             @(:roots (:client-context pair))))
      ;; Update roots
      (client/update-roots! (:client pair) (:client-context pair)
                            [{:uri "file:///new" :name "New Root"}])
      ;; Client context should be updated
      (is (= [{:uri "file:///new" :name "New Root"}]
             @(:roots (:client-context pair))))
      ;; Server should get the new roots when it requests them
      (Thread/sleep 500)
      (let [server-roots @(:roots (:server-context pair))]
        (is (= 1 (count server-roots)))
        (is (= "file:///new" (:uri (first server-roots)))))
      (shutdown-pair! pair))))

(deftest integration-logging-level-filter
  (testing "Server respects client-set log level for filtering"
    (let [log-messages (atom [])
          pair (create-piped-pair
                 {:name "log-filter-server", :version "1.0.0", :tools []}
                 {:client-info {:name "test-client", :version "1.0.0"},
                  :on-log-message (fn [msg] (swap! log-messages conj msg))})]
      (start-pair! pair)
      (client/initialize! (:client pair))
      ;; Set log level to "warning"
      (client/set-logging-level! (:client pair) "warning")
      (Thread/sleep 200)
      ;; Send messages at various levels
      (let [srv @(:protocol (:server-context pair))]
        (server/notify-log-message! srv "debug" "debug msg"
                                    :context (:server-context pair))
        (server/notify-log-message! srv "info" "info msg"
                                    :context (:server-context pair))
        (server/notify-log-message! srv "warning" "warning msg"
                                    :context (:server-context pair))
        (server/notify-log-message! srv "error" "error msg"
                                    :context (:server-context pair)))
      (Thread/sleep 500)
      ;; Only warning and above should come through
      (is (= 2 (count @log-messages)))
      (is (= #{"warning" "error"} (set (map :level @log-messages))))
      (shutdown-pair! pair))))
