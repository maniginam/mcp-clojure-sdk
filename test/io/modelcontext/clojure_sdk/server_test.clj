(ns io.modelcontext.clojure-sdk.server-test
  (:require [clojure.core.async :as async]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.mcp.errors :as mcp.errors]
            [io.modelcontext.clojure-sdk.server :as server]
            [io.modelcontext.clojure-sdk.test-helper :as h]
            [lsp4clj.lsp.requests :as lsp.requests]
            [lsp4clj.lsp.responses :as lsp.responses]))

;;; Tools
(def tool-greet
  {:name "greet",
   :description "Greet someone",
   :inputSchema {:type "object", :properties {"name" {:type "string"}}},
   :handler (fn [{:keys [name]}]
              {:type "text", :text (str "Hello, " name "!")})})

(def tool-echo
  {:name "echo",
   :description "Echo input",
   :inputSchema {:type "object",
                 :properties {"message" {:type "string"}},
                 :required ["message"]},
   :handler (fn [{:keys [message]}] {:type "text", :text message})})


;;; Prompts
(def prompt-analyze-code
  {:name "analyze-code",
   :description "Analyze code for potential improvements",
   :arguments
   [{:name "language", :description "Programming language", :required true}
    {:name "code", :description "The code to analyze", :required true}],
   :handler (fn analyze-code [args]
              {:messages [{:role "assistant",
                           :content
                           {:type "text",
                            :text (str "Analysis of "
                                       (:language args)
                                       " code:\n"
                                       "Here are potential improvements for:\n"
                                       (:code args))}}]})})

(def prompt-poem-about-code
  {:name "poem-about-code",
   :description "Write a poem describing what this code does",
   :arguments
   [{:name "poetry_type",
     :description
     "The style in which to write the poetry: sonnet, limerick, haiku",
     :required true}
    {:name "code",
     :description "The code to write poetry about",
     :required true}],
   :handler (fn [args]
              {:messages [{:role "assistant",
                           :content {:type "text",
                                     :text (str "Write a " (:poetry_type args)
                                                " Poem about:\n" (:code
                                                                   args))}}]})})

;;; Resources
(def resource-test-json
  {:description "Test JSON data",
   :mimeType "application/json",
   :name "Test Data",
   :uri "file:///data.json",
   :handler
   (fn read-resource [uri]
     {:uri uri, :mimeType "application/json", :blob "Hello from Test Data"})})

(def resource-test-file
  {:description "A test file",
   :mimeType "text/plain",
   :name "Test File",
   :uri "file:///test.txt",
   :handler
   (fn read-resource [uri]
     {:uri uri, :mimeType "text/plain", :text "Hello from Test File"})})

;;; Resource Templates
(def resource-template-user
  {:uriTemplate "file:///users/{userId}/profile",
   :name "User Profile",
   :description "A user profile resource",
   :mimeType "text/plain"})

;;; Example Server Spec
(def example-server-spec
  {:name "test-server",
   :version "1.0.0",
   :tools [tool-greet],
   :prompts [],
   :resources [resource-test-file]})

;;; Tests

(deftest server-basic-functionality
  (testing "Server creation and tool registration"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [tool-greet],
                                           :prompts [],
                                           :resources []})]
      (testing "Tool listing"
        (let [tools (-> @(:tools context)
                        vals
                        first
                        :tool)]
          (is (= "greet" (:name tools)))
          (is (= "Greet someone" (:description tools)))))
      (testing "Tool execution"
        (let [handler (-> @(:tools context)
                          (get "greet")
                          :handler)
              result (handler {:name "World"})]
          (is (= {:type "text", :text "Hello, World!"} result))))))
  (testing "Server creation with basic configuration"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources []})]
      (is (= "test-server" (get-in context [:server-info :name])))
      (is (= "1.0.0" (get-in context [:server-info :version])))
      (is (= {} @(:tools context)))
      (is (= {} @(:resources context)))
      (is (= {} @(:prompts context))))))

(deftest tool-registration
  (testing "Tool registration and validation"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools []})]
      (server/register-tool!
        context
        {:name "test-tool",
         :description "A test tool",
         :inputSchema {:type "object", :properties {"arg" {:type "string"}}}}
        (fn [_] {:type "text", :text "success"}))
      (is (= 1 (count @(:tools context))))
      (is (get @(:tools context) "test-tool")))))

(deftest tool-deregistration
  (testing "Deregistering a tool removes it and updates capabilities"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [tool-greet tool-echo]})]
      (is (= 2 (count @(:tools context))))
      (is (contains? @(:capabilities context) :tools))
      (server/deregister-tool! context "greet")
      (is (= 1 (count @(:tools context))))
      (is (nil? (get @(:tools context) "greet")))
      (is (get @(:tools context) "echo"))
      (server/deregister-tool! context "echo")
      (is (= 0 (count @(:tools context))))
      (is (not (contains? @(:capabilities context) :tools))))))

(deftest resource-deregistration
  (testing "Deregistering a resource removes it and updates capabilities"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :resources [resource-test-json resource-test-file]})]
      (is (= 2 (count @(:resources context))))
      (is (contains? @(:capabilities context) :resources))
      (server/deregister-resource! context "file:///data.json")
      (is (= 1 (count @(:resources context))))
      (is (nil? (get @(:resources context) "file:///data.json")))
      (server/deregister-resource! context "file:///test.txt")
      (is (= 0 (count @(:resources context))))
      (is (not (contains? @(:capabilities context) :resources))))))

(deftest prompt-deregistration
  (testing "Deregistering a prompt removes it and updates capabilities"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :prompts [prompt-analyze-code prompt-poem-about-code]})]
      (is (= 2 (count @(:prompts context))))
      (is (contains? @(:capabilities context) :prompts))
      (server/deregister-prompt! context "analyze-code")
      (is (= 1 (count @(:prompts context))))
      (is (nil? (get @(:prompts context) "analyze-code")))
      (server/deregister-prompt! context "poem-about-code")
      (is (= 0 (count @(:prompts context))))
      (is (not (contains? @(:capabilities context) :prompts))))))

(deftest resource-template-deregistration
  (testing "Deregistering a resource template removes it and updates capabilities"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :resource-templates [resource-template-user]})]
      (is (= 1 (count @(:resource-templates context))))
      (is (contains? @(:capabilities context) :resources))
      (server/deregister-resource-template! context "file:///users/{userId}/profile")
      (is (= 0 (count @(:resource-templates context))))
      (is (not (contains? @(:capabilities context) :resources))))))

(deftest completion-deregistration
  (testing "Deregistering a completion removes it and updates capabilities"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools []})]
      (server/register-completion! context "my-prompt" "arg1"
        (fn [_] {:values ["a" "b"]}))
      (is (contains? @(:capabilities context) :completions))
      (server/deregister-completion! context "my-prompt" "arg1")
      (is (empty? (get @(:completions context) "my-prompt")))
      (is (not (contains? @(:capabilities context) :completions))))))

(deftest initialization
  (testing "Connection initialization through initialize, 2024-11-05 version"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Client initialization"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      1
                      "initialize"
                      {:protocolVersion "2024-11-05",
                       :capabilities {:roots {:listChanged true}, :sampling {}},
                       :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
        (is (= (lsp.responses/response
                 1
                 {:protocolVersion "2024-11-05",
                  :capabilities {:logging {},
                                    :tools {:listChanged true}},
                  :serverInfo {:name "test-server", :version "1.0.0"}})
               (h/take-or-timeout (:output-ch server) 200))))
      (testing "Protocol version is stored in context"
        (is (= "2024-11-05" @(:protocol-version context))))
      (server/shutdown! server)))
  (testing "Connection initialization through initialize, 2025-03-26 version"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Client initialization"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      1
                      "initialize"
                      {:protocolVersion "2025-03-26",
                       :capabilities {:roots {:listChanged true}, :sampling {}},
                       :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
        (is (= (lsp.responses/response
                 1
                 {:protocolVersion "2025-03-26",
                  :capabilities {:logging {},
                                    :tools {:listChanged true}},
                  :serverInfo {:name "test-server", :version "1.0.0"}})
               (h/take-or-timeout (:output-ch server) 200))))
      (server/shutdown! server)))
  (testing "Connection initialization through initialize, unknown version"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Client initialization"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      1
                      "initialize"
                      {:protocolVersion "DRAFT-2025-v2",
                       :capabilities {:roots {:listChanged true}, :sampling {}},
                       :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
        (is (= (lsp.responses/response
                 1
                 {:protocolVersion "2025-03-26",
                  :capabilities {:logging {},
                                    :tools {:listChanged true}},
                  :serverInfo {:name "test-server", :version "1.0.0"}})
               (h/take-or-timeout (:output-ch server) 200))))
      (server/shutdown! server)))
  (testing "Initialize response includes instructions when configured"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo],
                     :instructions "Use the echo tool to repeat messages."})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request
                    1 "initialize"
                    {:protocolVersion "2025-03-26",
                     :capabilities {},
                     :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
      (let [response (h/take-or-timeout (:output-ch server) 200)
            result (:result response)]
        (is (= "Use the echo tool to repeat messages." (:instructions result))))
      (server/shutdown! server)))
  (testing "Initialize response omits instructions when not configured"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request
                    1 "initialize"
                    {:protocolVersion "2025-03-26",
                     :capabilities {},
                     :clientInfo {:name "ExampleClient", :version "1.0.0"}}))
      (let [response (h/take-or-timeout (:output-ch server) 200)
            result (:result response)]
        (is (nil? (:instructions result))))
      (server/shutdown! server)))
  (testing "Initialize tracks connected clients"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (is (empty? @(:connected-clients context)))
      (async/put! (:input-ch server)
                  (lsp.requests/request
                    1 "initialize"
                    {:protocolVersion "2025-03-26",
                     :capabilities {},
                     :clientInfo {:name "TestClient", :version "2.0.0"}}))
      (h/assert-take (:output-ch server))
      (is (= 1 (count @(:connected-clients context))))
      (let [[_ client-data] (first @(:connected-clients context))]
        (is (= {:name "TestClient", :version "2.0.0"}
               (:client-info client-data))))
      (server/shutdown! server))))

(deftest pagination-in-list-responses
  (testing "List tools with fewer items than page size returns no cursor"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= 1 (count (:tools result))))
        (is (nil? (:nextCursor result))
            "Should not include nextCursor when all items fit in one page"))
      (server/shutdown! server)))
  (testing "List resources with no cursor returns all items"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :resources [resource-test-file resource-test-json]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "resources/list" {}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= 2 (count (:resources result))))
        (is (nil? (:nextCursor result))))
      (server/shutdown! server)))
  (testing "List prompts with no cursor returns all items"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :prompts [prompt-analyze-code prompt-poem-about-code]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "prompts/list" {}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= 2 (count (:prompts result))))
        (is (nil? (:nextCursor result))))
      (server/shutdown! server)))
  (testing "Custom page-size limits items per page"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :page-size 1,
                     :tools [tool-echo
                             {:name "other"
                              :description "Other tool"
                              :inputSchema {:type "object"}
                              :handler (fn [_] "ok")}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= 1 (count (:tools result)))
            "Should return only 1 tool with page-size 1")
        (is (some? (:nextCursor result))
            "Should include nextCursor when more items exist"))
      (server/shutdown! server)))
  (testing "Cursor-based pagination walks through all pages"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :page-size 1,
                     :tools [tool-echo
                             {:name "tool-b"
                              :description "Tool B"
                              :inputSchema {:type "object"}
                              :handler (fn [_] "b")}
                             {:name "tool-c"
                              :description "Tool C"
                              :inputSchema {:type "object"}
                              :handler (fn [_] "c")}]})
          server (server/chan-server)
          _join (server/start! server context)]
      ;; First page
      (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
      (let [r1 (:result (h/assert-take (:output-ch server)))]
        (is (= 1 (count (:tools r1))))
        (is (some? (:nextCursor r1)))
        ;; Second page
        (async/put! (:input-ch server)
                    (lsp.requests/request 2 "tools/list"
                                          {:cursor (:nextCursor r1)}))
        (let [r2 (:result (h/assert-take (:output-ch server)))]
          (is (= 1 (count (:tools r2))))
          (is (some? (:nextCursor r2)))
          ;; Third (last) page
          (async/put! (:input-ch server)
                      (lsp.requests/request 3 "tools/list"
                                            {:cursor (:nextCursor r2)}))
          (let [r3 (:result (h/assert-take (:output-ch server)))]
            (is (= 1 (count (:tools r3))))
            (is (nil? (:nextCursor r3))
                "Last page should not have nextCursor"))))
      (server/shutdown! server))))

(deftest tool-execution
  (testing "Tool execution through protocol"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Tool list request"
        (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
        (is (= (lsp.responses/response 1
                                       {:tools [{:name "echo",
                                                 :description "Echo input",
                                                 :inputSchema
                                                 {:type "object",
                                                  :properties
                                                  {"message" {:type "string"}},
                                                  :required ["message"]}}]})
               (h/assert-take (:output-ch server)))))
      (testing "Tool execution request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "tools/call"
                                          {:name "echo",
                                           :arguments {:message "test"}}))
        (is (= (lsp.responses/response 2
                                       {:content [{:type "text",
                                                   :text "test"}]})
               (h/assert-take (:output-ch server)))))
      (testing "Invalid tool request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 3 "tools/call" {:name "invalid"}))
        (is (= (lsp.responses/error (lsp.responses/response 3)
                                    (mcp.errors/body :tool-not-found
                                                     {:tool-name "invalid"}))
               (h/assert-take (:output-ch server)))))
      (testing "Missing tool name returns invalid-params error"
        (async/put! (:input-ch server)
                    (lsp.requests/request 4 "tools/call" {:arguments {:x 1}}))
        (is (= (lsp.responses/error (lsp.responses/response 4)
                                    (mcp.errors/body :invalid-params
                                                     {:missing "name"}))
               (h/assert-take (:output-ch server)))))
      (server/shutdown! server)))
  (testing "Tool handler returning a tool-error response passes through"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "err-tool"
                              :description "Returns error"
                              :inputSchema {:type "object"}
                              :handler (fn [_] (server/tool-error "bad input"))}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "err-tool", :arguments {}}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (true? (:isError result)))
        (is (= [{:type "text", :text "bad input"}] (:content result))))
      (server/shutdown! server)))
  (testing "Tool handler returning a string is wrapped as text content"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "str-tool"
                              :description "Returns a string"
                              :inputSchema {:type "object"}
                              :handler (fn [_] "just a string")}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "str-tool", :arguments {}}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= [{:type "text", :text "just a string"}] (:content result))))
      (server/shutdown! server)))
  (testing "Tool handler returning nil produces empty content"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "nil-tool"
                              :description "Returns nil"
                              :inputSchema {:type "object"}
                              :handler (fn [_] nil)}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "nil-tool", :arguments {}}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= [] (:content result))))
      (server/shutdown! server))))

(deftest prompt-listing
  (testing "Listing available prompts"
    (let [context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [prompt-analyze-code
                                                     prompt-poem-about-code]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Prompts list request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1 "prompts/list" {}))
        (let [response (h/assert-take (:output-ch server))]
          (is (= 2 (count (:prompts (:result response)))))
          (let [analyze (first (:prompts (:result response)))
                poem (second (:prompts (:result response)))]
            (is (= "analyze-code" (:name analyze)))
            (is (= "Analyze code for potential improvements"
                   (:description analyze)))
            (is (= [{:name "language",
                     :description "Programming language",
                     :required true}
                    {:name "code",
                     :description "The code to analyze",
                     :required true}]
                   (:arguments analyze)))
            (is (= "poem-about-code" (:name poem)))
            (is (= "Write a poem describing what this code does"
                   (:description poem)))
            (is
              (=
                [{:name "poetry_type",
                  :description
                  "The style in which to write the poetry: sonnet, limerick, haiku",
                  :required true}
                 {:name "code",
                  :description "The code to write poetry about",
                  :required true}]
                (:arguments poem))))))
      (server/shutdown! server))))

(deftest prompt-getting
  (testing "Getting specific prompts"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [prompt-analyze-code
                                                     prompt-poem-about-code]})
          _join (server/start! server context)]
      (testing "Get analyze-code prompt"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1
                                          "prompts/get"
                                          {:name "analyze-code",
                                           :arguments {:language "Clojure",
                                                       :code "(defn foo [])"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:messages result))))
          (is
            (=
              "Analysis of Clojure code:\nHere are potential improvements for:\n(defn foo [])"
              (-> result
                  :messages
                  first
                  :content
                  :text)))))
      (testing "Get poem-about-code prompt"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "prompts/get"
                                          {:name "poem-about-code",
                                           :arguments {:poetry_type "haiku",
                                                       :code "(defn foo [])"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:messages result))))
          (is (= "Write a haiku Poem about:\n(defn foo [])"
                 (-> result
                     :messages
                     first
                     :content
                     :text)))))
      (testing "Invalid prompt request"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 3 "prompts/get" {:name "invalid-prompt"}))
        (is (= (lsp.responses/error (lsp.responses/response 3)
                                    (mcp.errors/body :prompt-not-found
                                                     {:prompt-name
                                                      "invalid-prompt"}))
               (h/assert-take (:output-ch server)))))
      (testing "Missing prompt name returns invalid-params error"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 4 "prompts/get" {:arguments {:x 1}}))
        (is (= (lsp.responses/error (lsp.responses/response 4)
                                    (mcp.errors/body :invalid-params
                                                     {:missing "name"}))
               (h/assert-take (:output-ch server)))))
      (server/shutdown! server))))

(deftest resource-listing
  (testing "Listing available resources"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources [resource-test-file
                                                       resource-test-json]})
          _join (server/start! server context)]
      (testing "Resources list request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1 "resources/list" {}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 2 (count (:resources result))))
          (let [file-resource (first (:resources result))
                json-resource (second (:resources result))]
            (is (= "file:///test.txt" (:uri file-resource)))
            (is (= "Test File" (:name file-resource)))
            (is (= "A test file" (:description file-resource)))
            (is (= "text/plain" (:mimeType file-resource)))
            (is (= "file:///data.json" (:uri json-resource)))
            (is (= "Test Data" (:name json-resource)))
            (is (= "Test JSON data" (:description json-resource)))
            (is (= "application/json" (:mimeType json-resource)))))
        (server/shutdown! server)))))

(deftest resource-reading
  (testing "Reading resources"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [],
                                           :resources [resource-test-file
                                                       resource-test-json]})
          _join (server/start! server context)]
      (testing "Read text file resource"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 2 "resources/read" {:uri "file:///test.txt"}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:contents result))))
          (let [content (first (:contents result))]
            (is (= "file:///test.txt" (:uri content)))
            (is (= "text/plain" (:mimeType content)))
            (is (contains? content :text)))))
      (testing "Read JSON resource"
        (async/put!
          (:input-ch server)
          (lsp.requests/request 3 "resources/read" {:uri "file:///data.json"}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:contents result))))
          (let [content (first (:contents result))]
            (is (= "file:///data.json" (:uri content)))
            (is (= "application/json" (:mimeType content)))
            (is (contains? content :blob)))))
      (testing "Invalid resource request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 4
                                          "resources/read"
                                          {:uri "file:///invalid.txt"}))
        (is (= (lsp.responses/error (lsp.responses/response 4)
                                    (mcp.errors/body :resource-not-found
                                                     {:uri
                                                      "file:///invalid.txt"}))
               (h/assert-take (:output-ch server)))))
      (testing "Missing resource URI returns invalid-params error"
        (async/put! (:input-ch server)
                    (lsp.requests/request 5 "resources/read" {}))
        (is (= (lsp.responses/error (lsp.responses/response 5)
                                    (mcp.errors/body :invalid-params
                                                     {:missing "uri"}))
               (h/assert-take (:output-ch server)))))
      (server/shutdown! server))))

(deftest resource-template-listing
  (testing "Listing resource templates"
    (let [server (server/chan-server)
          context (server/create-context!
                    {:name "test-server",
                     :version "1.0.0",
                     :tools [],
                     :resource-templates [resource-template-user]})
          _join (server/start! server context)]
      (testing "Resource templates list request"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1 "resources/templates/list" {}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= 1 (count (:resourceTemplates result))))
          (let [tmpl (first (:resourceTemplates result))]
            (is (= "file:///users/{userId}/profile" (:uriTemplate tmpl)))
            (is (= "User Profile" (:name tmpl))))))
      (server/shutdown! server))))

(deftest completion-complete
  (testing "Completion/complete returns completions for prompt arguments"
    (let [server (server/chan-server)
          context (server/create-context!
                    {:name "test-server",
                     :version "1.0.0",
                     :tools [],
                     :prompts [{:name "greet",
                                :description "Greet someone",
                                :arguments [{:name "name", :required true}],
                                :handler (fn [args]
                                           {:messages
                                            [{:role "assistant",
                                              :content
                                              {:type "text",
                                               :text (str "Hello, "
                                                          (:name args))}}]})}]})
          _join (server/start! server context)]
      (server/register-completion!
        context "greet" "name"
        (fn [partial-value]
          {:values (filterv #(clojure.string/starts-with? % partial-value)
                            ["Alice" "Bob" "Charlie"])}))
      (testing "Returns matching completions"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      1 "completion/complete"
                      {:ref {:type "ref/prompt", :name "greet"},
                       :argument {:name "name", :value "A"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= ["Alice"] (get-in result [:completion :values])))))
      (testing "Returns empty for no matches"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      2 "completion/complete"
                      {:ref {:type "ref/prompt", :name "greet"},
                       :argument {:name "name", :value "Z"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= [] (get-in result [:completion :values])))))
      (testing "Returns empty for unregistered completion"
        (async/put! (:input-ch server)
                    (lsp.requests/request
                      3 "completion/complete"
                      {:ref {:type "ref/prompt", :name "unknown"},
                       :argument {:name "arg", :value "x"}}))
        (let [response (h/assert-take (:output-ch server))
              result (:result response)]
          (is (= [] (get-in result [:completion :values])))))
      (server/shutdown! server))))

(deftest resource-subscribe-unsubscribe
  (testing "Client can subscribe and unsubscribe to resource updates"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :resources [resource-test-file]})
          _join (server/start! server context)]
      (testing "Subscribe to a resource"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1
                                          "resources/subscribe"
                                          {:uri "file:///test.txt"}))
        (let [response (h/assert-take (:output-ch server))]
          (is (= {} (:result response)))))
      (testing "Subscription is tracked"
        (is (contains? @(:subscriptions context) "file:///test.txt")))
      (testing "Unsubscribe from a resource"
        (async/put! (:input-ch server)
                    (lsp.requests/request 2
                                          "resources/unsubscribe"
                                          {:uri "file:///test.txt"}))
        (let [response (h/assert-take (:output-ch server))]
          (is (= {} (:result response)))))
      (testing "Subscription is removed"
        (is (not (contains? @(:subscriptions context) "file:///test.txt"))))
      (server/shutdown! server))))

(deftest resource-handler-error
  (testing "Resource handler that throws returns error response"
    (let [failing-resource {:uri "file:///failing",
                            :name "Failing Resource",
                            :description "A resource that throws",
                            :mimeType "text/plain",
                            :handler (fn [_uri]
                                       (throw (ex-info "Resource read failed"
                                                       {})))}
          server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :resources [failing-resource]})
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1
                                        "resources/read"
                                        {:uri "file:///failing"}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (some? result) "Should return a result, not a protocol error")
        (is (true? (:isError result))
            "Should flag the result as an error")
        (is (some? (:contents result))
            "Should include error content"))
      (server/shutdown! server))))

(deftest prompt-handler-error
  (testing "Prompt handler that throws returns error response"
    (let [failing-prompt {:name "failing-prompt",
                          :description "A prompt that throws",
                          :handler (fn [_args]
                                     (throw (ex-info "Prompt generation failed"
                                                     {})))}
          server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [],
                                           :prompts [failing-prompt]})
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1
                                        "prompts/get"
                                        {:name "failing-prompt"}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (some? result) "Should return a result, not a protocol error")
        (is (true? (:isError result))
            "Should flag the result as an error")
        (is (some? (:messages result))
            "Should include error messages"))
      (server/shutdown! server))))

(deftest coerce-tool-response-test
  (testing "Coercing tool responses"
    (testing "Tool with sequential response"
      (let [tool {:name "list-maker",
                  :description "Creates a list of items",
                  :inputSchema {:type "object",
                                :properties {"count" {:type "number"}}}}
            handler-response [{:type "text", :text "item 1"}
                              {:type "text", :text "item 2"}]
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:type "text", :text "item 1"}
                          {:type "text", :text "item 2"}]}
               coerced))
        (is
          (not (contains? coerced :structuredContent))
          "Should not include structuredContent when tool has no outputSchema")))
    (testing "Tool with non-sequential response"
      (let [tool {:name "echo",
                  :description "Echoes input",
                  :inputSchema {:type "object",
                                :properties {"message" {:type "string"}}}}
            handler-response {:type "text", :text "single item"}
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:type "text", :text "single item"}]} coerced))
        (is (vector? (:content coerced))
            "Response should be wrapped in a vector")))
    (testing "Tool with outputSchema"
      (let [tool {:name "calculator",
                  :description "Performs calculations",
                  :inputSchema {:type "object",
                                :properties {"expression" {:type "string"}}},
                  :outputSchema {:type "object",
                                 :properties {"result" {:type "number"}}}}
            handler-response {:result 42}
            coerced (server/coerce-tool-response tool handler-response)]
        (is (= {:content [{:result 42}], :structuredContent [{:result 42}]}
               coerced))
        (is (contains? coerced :structuredContent)
            "Should include structuredContent when tool has outputSchema")))))

(deftest request-roots-list
  (testing "Server requests roots/list from client"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      (testing "Server sends roots/list and receives response"
        ;; Start the roots/list request from the server in a separate thread
        (let [result-promise (future (server/request-roots-list! server))]
          ;; Simulate client response: read the request from output-ch
          (let [request (h/assert-take (:output-ch server))]
            (is (= "roots/list" (:method request)))
            (is (some? (:id request)))
            ;; Simulate client sending response back
            (async/put! (:input-ch server)
                        {:jsonrpc "2.0",
                         :id (:id request),
                         :result {:roots [{:uri "file:///home/user/project",
                                           :name "My Project"}
                                          {:uri "file:///home/user/docs"}]}}))
          ;; Get the result
          (let [roots (deref result-promise 1000 :timeout)]
            (is (not= :timeout roots))
            (is (= 2 (count roots)))
            (is (= "file:///home/user/project" (:uri (first roots))))
            (is (= "My Project" (:name (first roots))))
            (is (= "file:///home/user/docs" (:uri (second roots)))))))
      (server/shutdown! server))))

(deftest roots-list-changed-notification
  (testing "Server handles roots/list_changed notification and refreshes roots"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0", :tools [tool-echo]})
          server (server/chan-server)
          _join (server/start! server context)]
      ;; Store the server reference in context for notification handler access
      (reset! (:protocol context) server)
      (testing "Context has an empty roots atom"
        (is (= [] @(:roots context))))
      (testing "Refresh roots updates the context"
        (let [result-promise (future (server/refresh-roots! server context))]
          (let [request (h/assert-take (:output-ch server))]
            (is (= "roots/list" (:method request)))
            (async/put! (:input-ch server)
                        {:jsonrpc "2.0",
                         :id (:id request),
                         :result {:roots [{:uri "file:///initial/path"}]}}))
          (deref result-promise 1000 :timeout)
          (is (= [{:uri "file:///initial/path"}] @(:roots context)))))
      (server/shutdown! server))))

(deftest set-logging-level
  (testing "Client can set the server's logging level"
    (let [server (server/chan-server)
          context (server/create-context! {:name "test-server",
                                           :version "1.0.0",
                                           :tools [tool-echo]})
          _join (server/start! server context)]
      (testing "Setting a valid log level stores it in context"
        (async/put! (:input-ch server)
                    (lsp.requests/request 1
                                          "logging/setLevel"
                                          {:level "warning"}))
        (let [response (h/assert-take (:output-ch server))]
          (is (= {} (:result response)))))
      (testing "Log level is stored in context"
        (is (= "warning" @(:log-level context))))
      (testing "Log messages at or above threshold are sent"
        (server/notify-log-message! server "error" "An error occurred"
                                    :context context)
        (let [msg (h/assert-take (:output-ch server))]
          (is (= "notifications/message" (:method msg)))
          (is (= "error" (get-in msg [:params :level])))))
      (testing "Log messages below threshold are suppressed"
        (server/notify-log-message! server "debug" "Debug info"
                                    :context context)
        (is (= :timeout (h/take-or-timeout (:output-ch server) 100))
            "Debug message should be suppressed when level is warning"))
      (server/shutdown! server))))

(deftest response-helpers
  (testing "text-content creates a text content block"
    (is (= {:type "text", :text "hello"} (server/text-content "hello"))))
  (testing "image-content creates an image content block"
    (is (= {:type "image", :data "base64data", :mimeType "image/png"}
           (server/image-content "base64data" "image/png"))))
  (testing "tool-error creates an error tool response"
    (let [result (server/tool-error "something went wrong")]
      (is (true? (:isError result)))
      (is (= [{:type "text", :text "something went wrong"}] (:content result)))))
  (testing "prompt-message creates a prompt message"
    (is (= {:role "assistant", :content {:type "text", :text "Hello!"}}
           (server/prompt-message "assistant" "Hello!")))))

(deftest tool-helper
  (testing "tool helper creates tool maps"
    (testing "with properties and handler"
      (let [t (server/tool "add" "Add numbers"
                           {"a" {:type "number"} "b" {:type "number"}}
                           (fn [{:keys [a b]}] (+ a b)))]
        (is (= "add" (:name t)))
        (is (= "Add numbers" (:description t)))
        (is (= "object" (get-in t [:inputSchema :type])))
        (is (= #{"a" "b"} (set (get-in t [:inputSchema :required]))))
        (is (ifn? (:handler t)))))
    (testing "with no properties"
      (let [t (server/tool "noop" "Does nothing" (fn [_] nil))]
        (is (= {} (get-in t [:inputSchema :properties])))
        (is (= [] (get-in t [:inputSchema :required])))))
    (testing "with explicit required list"
      (let [t (server/tool "greet" "Greet"
                           {"name" {:type "string"} "title" {:type "string"}}
                           ["name"]
                           (fn [{:keys [name]}] name))]
        (is (= ["name"] (get-in t [:inputSchema :required])))))))

(deftest resource-helper
  (testing "resource helper creates resource maps"
    (let [r (server/resource "file:///test.txt" "Test File" identity)]
      (is (= "file:///test.txt" (:uri r)))
      (is (= "Test File" (:name r)))
      (is (= "text/plain" (:mimeType r)))
      (is (ifn? (:handler r))))
    (let [r (server/resource "file:///img.png" "Image" "image/png" identity)]
      (is (= "image/png" (:mimeType r))))))

(deftest prompt-helper
  (testing "prompt helper creates prompt maps"
    (let [p (server/prompt "greet" "Greet someone" identity)]
      (is (= "greet" (:name p)))
      (is (= "Greet someone" (:description p)))
      (is (nil? (:arguments p)))
      (is (ifn? (:handler p))))
    (let [p (server/prompt "greet" "Greet"
                           [{:name "name" :required true}]
                           identity)]
      (is (= [{:name "name" :required true}] (:arguments p))))))

(deftest request-meta-binding
  (testing "Tool handler can access *request-meta* during execution"
    (let [captured-meta (atom nil)
          context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "meta-tool"
                              :description "Captures request meta"
                              :inputSchema {:type "object"}
                              :handler (fn [_]
                                         (reset! captured-meta server/*request-meta*)
                                         {:type "text", :text "ok"})}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "meta-tool",
                                         :arguments {},
                                         :_meta {:progressToken "tok-123"}}))
      (h/assert-take (:output-ch server))
      (is (= {:progressToken "tok-123"} @captured-meta))
      (server/shutdown! server)))

  (testing "Tool handler can access *server* during execution"
    (let [captured-server (atom nil)
          context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "server-tool"
                              :description "Captures server ref"
                              :inputSchema {:type "object"}
                              :handler (fn [_]
                                         (reset! captured-server server/*server*)
                                         {:type "text", :text "ok"})}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "server-tool", :arguments {}}))
      (h/assert-take (:output-ch server))
      (is (some? @captured-server))
      (server/shutdown! server)))

  (testing "Tool handler can access *context* during execution"
    (let [captured-context (atom nil)
          context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :tools [{:name "context-tool"
                              :description "Captures context ref"
                              :inputSchema {:type "object"}
                              :handler (fn [_]
                                         (reset! captured-context server/*context*)
                                         {:type "text", :text "ok"})}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "tools/call"
                                        {:name "context-tool", :arguments {}}))
      (h/assert-take (:output-ch server))
      (is (some? @captured-context))
      (is (= "test-server" (get-in @captured-context [:server-info :name])))
      (server/shutdown! server)))

  (testing "Resource handler can access *server* and *context*"
    (let [captured (atom {})
          context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :resources [{:uri "test://res"
                                  :name "test-res"
                                  :handler (fn [_]
                                             (reset! captured {:server server/*server*
                                                               :context server/*context*})
                                             [{:uri "test://res" :text "ok"}])}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "resources/read" {:uri "test://res"}))
      (h/assert-take (:output-ch server))
      (is (some? (:server @captured)))
      (is (some? (:context @captured)))
      (server/shutdown! server)))

  (testing "Prompt handler can access *server* and *context*"
    (let [captured (atom {})
          context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :prompts [{:name "test-prompt"
                                :description "Test"
                                :handler (fn [_]
                                           (reset! captured {:server server/*server*
                                                             :context server/*context*})
                                           {:messages [{:role "assistant"
                                                        :content {:type "text" :text "ok"}}]})}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "prompts/get" {:name "test-prompt"}))
      (h/assert-take (:output-ch server))
      (is (some? (:server @captured)))
      (is (some? (:context @captured)))
      (server/shutdown! server))))

(deftest duplicate-registration
  (testing "Registering a tool with the same name replaces the previous one"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0"})
          _ (server/register-tool! context
                                   {:name "calc"
                                    :description "Version 1"
                                    :inputSchema {:type "object"}}
                                   (fn [_] "v1"))
          _ (server/register-tool! context
                                   {:name "calc"
                                    :description "Version 2"
                                    :inputSchema {:type "object"}}
                                   (fn [_] "v2"))
          server (server/chan-server)
          _join (server/start! server context)]
      ;; Should have 1 tool, not 2
      (async/put! (:input-ch server) (lsp.requests/request 1 "tools/list" {}))
      (let [result (:result (h/assert-take (:output-ch server)))]
        (is (= 1 (count (:tools result))))
        (is (= "Version 2" (:description (first (:tools result))))))
      ;; Should use the new handler
      (async/put! (:input-ch server)
                  (lsp.requests/request 2 "tools/call"
                                        {:name "calc", :arguments {}}))
      (let [result (:result (h/assert-take (:output-ch server)))]
        (is (= "v2" (-> result :content first :text))))
      (server/shutdown! server))))

(deftest validate-spec-test
  (testing "Validating server specifications"
    (let [valid-tool {:name "valid-tool",
                      :description "A valid tool",
                      :inputSchema {:type "object"},
                      :handler identity}
          valid-resource {:uri "file:///valid.txt",
                          :name "Valid Resource",
                          :handler (fn [_]
                                     {:uri "file:///valid.txt", :text "valid"})}
          valid-prompt {:name "valid-prompt", :handler (fn [_] {:messages []})}
          valid-server-info {:name "test-server", :version "1.0.0"}
          invalid-tool-schema {:name "invalid-tool",
                               :description "Bad schema",
                               :inputSchema {:invalid "schema"}, ; Invalid key
                               :handler identity}
          invalid-resource-missing-name
            {:uri "file:///invalid.txt",
             ;; Missing :name
             :handler (fn [_] {:uri "file:///invalid.txt", :text "invalid"})}
          invalid-prompt-missing-name {;; Missing :name
                                       :handler (fn [_] {:messages []})}
          invalid-handler-tool {:name "invalid-handler-tool",
                                :description "Non-fn handler",
                                :inputSchema {:type "object"},
                                :handler "not-a-function"}
          invalid-server-info-missing-name {:version "1.0.0"}
          invalid-server-info-missing-version {:name "test-server"}
          invalid-server-info-bad-type {:name 123, :version "1.0.0"}]
      (testing "Valid specification"
        (is (server/validate-spec! (merge valid-server-info
                                          {:tools [valid-tool],
                                           :prompts [valid-prompt],
                                           :resources [valid-resource]}))
            "A completely valid spec should not throw"))
      (testing "Empty specification (requires server info)"
        (is (server/validate-spec! valid-server-info)
            "A spec with only server info should be valid")
        (is (server/validate-spec!
              (merge valid-server-info {:tools [], :prompts [], :resources []}))
            "A spec with server info and empty lists should be valid"))
      (testing "Partially valid specification (requires server info)"
        (is (server/validate-spec! (merge valid-server-info
                                          {:tools [valid-tool]}))
            "A spec with only valid tools should be valid")
        (is (server/validate-spec! (merge valid-server-info
                                          {:prompts [valid-prompt]}))
            "A spec with only valid prompts should be valid")
        (is (server/validate-spec! (merge valid-server-info
                                          {:resources [valid-resource]}))
            "A spec with only valid resources should be valid"))
      (testing "Invalid server info - missing name"
        (is (thrown? Exception
                     (server/validate-spec! invalid-server-info-missing-name))
            "Spec with missing server name should throw"))
      (testing "Invalid server info - missing version"
        (is (thrown? Exception
                     (server/validate-spec!
                       invalid-server-info-missing-version))
            "Spec with missing server version should throw"))
      (testing "Invalid server info - bad type"
        (is (thrown? Exception
                     (server/validate-spec! invalid-server-info-bad-type))
            "Spec with invalid server info type should throw"))
      (testing "Invalid tool schema"
        (is (thrown? Exception
                     (server/validate-spec! (merge valid-server-info
                                                   {:tools
                                                    [invalid-tool-schema]})))
            "Spec with invalid tool schema should throw"))
      (testing "Invalid resource definition"
        (is (thrown? Exception
                     (server/validate-spec!
                       (merge valid-server-info
                              {:resources [invalid-resource-missing-name]})))
            "Spec with invalid resource definition should throw"))
      (testing "Invalid prompt definition"
        (is (thrown? Exception
                     (server/validate-spec!
                       (merge valid-server-info
                              {:prompts [invalid-prompt-missing-name]})))
            "Spec with invalid prompt definition should throw"))
      (testing "Invalid handler type"
        (is (thrown? Exception
                     (server/validate-spec! (merge valid-server-info
                                                   {:tools
                                                    [invalid-handler-tool]})))
            "Spec with a non-function handler should throw")))))

(deftest progress-notification
  (testing "Server sends progress notification to client"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0"})
          server (server/chan-server)
          _join (server/start! server context)]
      (server/notify-progress! server "token-1" 50 100)
      (let [msg (h/assert-take (:output-ch server))]
        (is (= "notifications/progress" (:method msg)))
        (is (= "token-1" (get-in msg [:params :progressToken])))
        (is (= 50 (get-in msg [:params :progress])))
        (is (= 100 (get-in msg [:params :total]))))
      (server/shutdown! server)))
  (testing "Progress notification without total"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0"})
          server (server/chan-server)
          _join (server/start! server context)]
      (server/notify-progress! server "token-2" 10)
      (let [msg (h/assert-take (:output-ch server))]
        (is (= "notifications/progress" (:method msg)))
        (is (= 10 (get-in msg [:params :progress])))
        (is (nil? (get-in msg [:params :total]))))
      (server/shutdown! server))))

(deftest resource-template-read
  (testing "Reading a URI matching a resource template invokes the template handler"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0",
                     :resource-templates
                     [{:uriTemplate "file:///users/{userId}/profile",
                       :name "User Profile",
                       :description "A user's profile",
                       :mimeType "text/plain",
                       :handler (fn [uri]
                                  {:uri uri,
                                   :mimeType "text/plain",
                                   :text (str "Profile for URI: " uri)})}]})
          server (server/chan-server)
          _join (server/start! server context)]
      (async/put! (:input-ch server)
                  (lsp.requests/request 1 "resources/read"
                                        {:uri "file:///users/42/profile"}))
      (let [response (h/assert-take (:output-ch server))
            result (:result response)]
        (is (= "Profile for URI: file:///users/42/profile"
               (-> result :contents first :text))))
      (server/shutdown! server))))

(deftest cancellation-tracking
  (testing "Cancelled request is tracked in context"
    (let [context (server/create-context!
                    {:name "test-server", :version "1.0.0"})
          server (server/chan-server)
          _join (server/start! server context)]
      ;; Send a cancelled notification
      (async/put! (:input-ch server)
                  (lsp.requests/notification "notifications/cancelled"
                                            {:requestId "req-42"
                                             :reason "User cancelled"}))
      ;; Give it time to process
      (Thread/sleep 100)
      (is (server/cancelled? context "req-42")
          "Request should be marked as cancelled")
      (is (not (server/cancelled? context "req-99"))
          "Non-cancelled request should not be marked")
      (server/shutdown! server))))
