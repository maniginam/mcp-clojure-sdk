(ns io.modelcontext.clojure-sdk.server
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.mcp.errors :as mcp.errors]
            [io.modelcontext.clojure-sdk.specs :as specs]
            [lsp4clj.coercer :as coercer]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log]))

;;; Response Helpers
;; Convenience functions for constructing well-formed MCP responses

(defn text-content
  "Create a text content block for tool responses."
  [text]
  {:type "text", :text text})

(defn image-content
  "Create an image content block for tool responses."
  [data mime-type]
  {:type "image", :data data, :mimeType mime-type})

(defn error-content
  "Create an error tool response."
  [message]
  {:type "text", :text message, :isError true})

(defn prompt-message
  "Create a prompt message for prompt handler responses."
  ([role text]
   {:role role, :content {:type "text", :text text}})
  ([role content-type content-data]
   {:role role, :content (merge {:type content-type} content-data)}))

;;; Helpers
;; Logging and Spec Checking
(defmacro conform-or-log
  "Provides log function for conformation, while preserving line numbers."
  [spec value]
  (let [fmeta (assoc (meta &form)
                :file *file*
                :ns-str (str *ns*))]
    `(coercer/conform-or-log
       (fn [& args#]
         (cond (= 2 (count args#)) (log/error :msg (first args#)
                                              :explain (second args#)
                                              :meta ~fmeta)
               (= 4 (count args#)) (log/error :ex (first args#)
                                              :msg (second args#)
                                              :spec ~spec
                                              :value ~value
                                              :meta ~fmeta)
               :else (throw (ex-info "Unknown Conform Error" {:args args#}))))
       ~spec
       ~value)))

;;; Pagination

(def default-page-size 50)

(defn- paginate
  "Apply cursor-based pagination to a collection of items.
   cursor is an offset string. page-size defaults to default-page-size."
  ([items cursor] (paginate items cursor default-page-size))
  ([items cursor page-size]
   (let [offset (if cursor
                  (try (Long/parseLong cursor) (catch Exception _ 0))
                  0)
         page (vec (take page-size (drop offset items)))
         next-offset (+ offset (count page))
         has-more? (< next-offset (count items))]
     (cond-> {:items page}
       has-more? (assoc :nextCursor (str next-offset))))))

;;; Helper functions for handling various requests

(defn store-client-info!
  [context client-info client-capabilities]
  (let [client-id (random-uuid)]
    (swap! (:connected-clients context) assoc
      client-id
      {:client-info client-info, :capabilities client-capabilities})
    client-id))

(defn- supported-protocol-version
  "Return the version of MCP protocol as part of connection initialization."
  [version]
  ;; [ref: version_negotiation]
  (if ((set specs/supported-protocol-versions) version)
    version
    (first specs/supported-protocol-versions)))

(defn- handle-initialize
  [context params]
  (let [client-info (:clientInfo params)
        client-capabilities (:capabilities params)
        server-info (:server-info context)
        server-capabilities @(:capabilities context)
        negotiated-version (supported-protocol-version (:protocolVersion params))
        client-id (store-client-info! context client-info client-capabilities)]
    (log/trace :fn :handle-initialize
               :msg "[Initialize] Client connected!"
               :client-info client-info
               :client-id client-id
               :protocol-version negotiated-version)
    (reset! (:protocol-version context) negotiated-version)
    (cond-> {:protocolVersion negotiated-version,
             :capabilities server-capabilities,
             :serverInfo server-info}
      (:instructions context)
      (assoc :instructions (:instructions context)))))

(defn- handle-ping [_context _params] (log/trace :fn :handle-ping) {})

(defn- handle-list-tools
  [context params]
  (log/trace :fn :handle-list-tools)
  (let [all-tools (mapv :tool (vals @(:tools context)))
        {:keys [items nextCursor]} (paginate all-tools (:cursor params)
                            (or (:page-size context) default-page-size))]
    (cond-> {:tools items}
      nextCursor (assoc :nextCursor nextCursor))))

(defn coerce-tool-response
  "Coerces a tool response into the expected format.
   If the response is not sequential, wraps it in a vector.
   If the tool has an outputSchema, adds structuredContent."
  [tool response]
  (let [response (if (sequential? response) (vec response) [response])
        base-map {:content response}]
    ;; @TODO: [ref: structured-content-should-match-output-schema-exactly]
    (cond-> base-map (:outputSchema tool) (assoc :structuredContent response))))

(defn- handle-call-tool
  [context params]
  (log/trace :fn :handle-call-tool
             :tool (:name params)
             :args (:arguments params))
  (let [tool-name (:name params)]
    (if-not tool-name
      {:error (mcp.errors/body :invalid-params {:missing "name"})}
      (let [tools @(:tools context)
            arguments (:arguments params)]
        (if-let [{:keys [tool handler]} (get tools tool-name)]
          (try (coerce-tool-response tool (handler arguments))
               (catch Exception e
                 {:content [{:type "text", :text (str "Error: " (.getMessage e))}],
                  :isError true}))
          (do
            (log/debug :fn :handle-call-tool :tool tool-name :error :tool-not-found)
            {:error (mcp.errors/body :tool-not-found {:tool-name tool-name})}))))))

(defn- handle-list-resources
  [context params]
  (log/trace :fn :handle-list-resources)
  (let [all-resources (mapv :resource (vals @(:resources context)))
        {:keys [items nextCursor]} (paginate all-resources (:cursor params)
                                            (or (:page-size context) default-page-size))]
    (cond-> {:resources items}
      nextCursor (assoc :nextCursor nextCursor))))

(defn- handle-read-resource
  [context params]
  (log/trace :fn :handle-read-resource :resource (:uri params))
  (let [uri (:uri params)]
    (if-not uri
      {:error (mcp.errors/body :invalid-params {:missing "uri"})}
      (let [resources @(:resources context)]
        (if-let [{:keys [handler]} (get resources uri)]
          (try {:contents [(handler uri)]}
               (catch Exception e
                 {:contents [{:uri uri,
                              :mimeType "text/plain",
                              :text (str "Error: " (.getMessage e))}],
                  :isError true}))
          (do (log/debug :fn :handle-read-resource
                         :resource uri
                         :error :resource-not-found)
              {:error (mcp.errors/body :resource-not-found {:uri uri})}))))))

(defn- handle-list-prompts
  [context params]
  (log/trace :fn :handle-list-prompts)
  (let [all-prompts (mapv :prompt (vals @(:prompts context)))
        {:keys [items nextCursor]} (paginate all-prompts (:cursor params)
                                            (or (:page-size context) default-page-size))]
    (cond-> {:prompts items}
      nextCursor (assoc :nextCursor nextCursor))))

(defn- handle-get-prompt
  [context params]
  (log/trace :fn :handle-get-prompt
             :prompt (:name params)
             :args (:arguments params))
  (let [prompt-name (:name params)]
    (if-not prompt-name
      {:error (mcp.errors/body :invalid-params {:missing "name"})}
      (let [prompts @(:prompts context)
            arguments (:arguments params)]
        (if-let [{:keys [handler]} (get prompts prompt-name)]
          (try (handler arguments)
               (catch Exception e
                 {:messages [{:role "assistant",
                              :content {:type "text",
                                        :text (str "Error: " (.getMessage e))}}],
                  :isError true}))
          (do (log/debug :fn :handle-get-prompt
                         :prompt prompt-name
                         :error :prompt-not-found)
              {:error (mcp.errors/body :prompt-not-found
                                       {:prompt-name prompt-name})}))))))

;;; Requests and Notifications

;; [ref: initialize_request]
(defmethod lsp.server/receive-request "initialize"
  [_ context params]
  (log/trace :fn :receive-request :method "initialize" :params params)
  ;; [tag: log_bad_input_params]
  ;;
  ;; If the input is non-conformant, we should log it. But we shouldn't
  ;; take any other action. The principle we want to follow is Postel's
  ;; law: https://en.wikipedia.org/wiki/Robustness_principle
  (conform-or-log ::specs/initialize-request params)
  (->> params
       (handle-initialize context)
       (conform-or-log ::specs/initialize-response)))

;; [ref: initialized_notification]
(defmethod lsp.server/receive-notification "notifications/initialized"
  [_ _ params]
  (conform-or-log ::specs/initialized-notification params))

;; [ref: ping_request]
(defmethod lsp.server/receive-request "ping"
  [_ context params]
  (log/trace :fn :receive-request :method "ping" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/ping-request params)
  (->> params
       (handle-ping context)))

;; [ref: list_tools_request]
(defmethod lsp.server/receive-request "tools/list"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-tools-request params)
  (->> params
       (handle-list-tools context)
       (conform-or-log ::specs/list-tools-response)))

;; [ref: call_tool_request]
(defmethod lsp.server/receive-request "tools/call"
  [_ context params]
  (log/trace :fn :receive-request :method "tools/call" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/call-tool-request params)
  (->> params
       (handle-call-tool context)
       (conform-or-log ::specs/call-tool-response)))

;; [ref: list_resources_request]
(defmethod lsp.server/receive-request "resources/list"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resources-request params)
  (->> params
       (handle-list-resources context)
       (conform-or-log ::specs/list-resources-response)))

;; [ref: read_resource_request]
(defmethod lsp.server/receive-request "resources/read"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/read" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/read-resource-request params)
  (->> params
       (handle-read-resource context)
       (conform-or-log ::specs/read-resource-response)))

;; [ref: list_prompts_request]
(defmethod lsp.server/receive-request "prompts/list"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/list" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-prompts-request params)
  (->> params
       (handle-list-prompts context)
       (conform-or-log ::specs/list-prompts-response)))

;; [ref: get_prompt_request]
(defmethod lsp.server/receive-request "prompts/get"
  [_ context params]
  (log/trace :fn :receive-request :method "prompts/get" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/get-prompt-request params)
  (->> params
       (handle-get-prompt context)
       (conform-or-log ::specs/get-prompt-response)))

;;; Additional Request Handlers

(defn- handle-list-resource-templates
  [context params]
  (log/trace :fn :handle-list-resource-templates)
  (let [all-templates (vec (vals @(:resource-templates context)))
        {:keys [items nextCursor]} (paginate all-templates (:cursor params)
                                            (or (:page-size context) default-page-size))]
    (cond-> {:resourceTemplates items}
      nextCursor (assoc :nextCursor nextCursor))))

;; [ref: list_resource_templates_request]
(defmethod lsp.server/receive-request "resources/templates/list"
  [_ context params]
  (log/trace :fn :receive-request
             :method "resources/templates/list"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/list-resource-templates-request params)
  (->> params
       (handle-list-resource-templates context)
       (conform-or-log ::specs/list-resource-templates-response)))

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod lsp.server/receive-request "resources/subscribe"
  [_ context params]
  (log/trace :fn :receive-request :method "resources/subscribe" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  (swap! (:subscriptions context) update (:uri params) (fnil conj #{}) :subscribed)
  {})

;; [ref: resource_subscribe_unsubscribe_request]
(defmethod lsp.server/receive-request "resources/unsubscribe"
  [_ context params]
  (log/trace :fn :receive-request
             :method "resources/unsubscribe"
             :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/resource-subscribe-unsubscribe-request params)
  (swap! (:subscriptions context) dissoc (:uri params))
  {})

;; [ref: set_logging_level_request]
(defmethod lsp.server/receive-request "logging/setLevel"
  [_ context params]
  (log/trace :fn :receive-request :method "logging/setLevel" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/set-logging-level-request params)
  (reset! (:log-level context) (:level params))
  {})

(defn- handle-complete
  [context params]
  (log/trace :fn :handle-complete :ref (:ref params) :argument (:argument params))
  (let [completions @(:completions context)
        ref (:ref params)
        ref-type (:type ref)
        ref-key (case ref-type
                  "ref/prompt" (:name ref)
                  "ref/resource" (:uri ref)
                  nil)
        arg-name (get-in params [:argument :name])
        arg-value (get-in params [:argument :value])
        handler (get-in completions [ref-key arg-name])]
    (if handler
      {:completion (handler arg-value)}
      {:completion {:values []}})))

;; [ref: complete_request]
(defmethod lsp.server/receive-request "completion/complete"
  [_ context params]
  (log/trace :fn :receive-request :method "completion/complete" :params params)
  ;; [ref: log_bad_input_params]
  (conform-or-log ::specs/complete-request params)
  (->> params
       (handle-complete context)
       (conform-or-log ::specs/complete-response)))

;;; Notification Handlers (received from client)

;; [ref: cancelled_notification]
(defmethod lsp.server/receive-notification "notifications/cancelled"
  [_ context params]
  (log/trace :fn :receive-notification
             :method "notifications/cancelled"
             :request-id (:requestId params)
             :reason (:reason params))
  (conform-or-log ::specs/cancelled-notification params)
  (when-let [request-id (:requestId params)]
    (swap! (:cancelled-requests context) conj request-id)))

(defn cancelled?
  "Check if a request has been cancelled by the client.
   Tool handlers can call this to check if they should abort work."
  [context request-id]
  (contains? @(:cancelled-requests context) request-id))

;; [ref: progress_notification]
(defmethod lsp.server/receive-notification "notifications/progress"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/progress"
             :progress-token (:progressToken params)
             :progress (:progress params))
  (conform-or-log ::specs/progress-notification params))

;; [ref: progress_notification_send]
(defn notify-progress!
  "Send a progress notification to the client for a long-running request.
   progress-token is the token from the request's _meta.progressToken.
   progress is a number indicating progress so far (should increase).
   total is optional — the total amount of work if known."
  ([server progress-token progress]
   (lsp.server/send-notification server
                                 "notifications/progress"
                                 {:progressToken progress-token,
                                  :progress progress}))
  ([server progress-token progress total]
   (lsp.server/send-notification server
                                 "notifications/progress"
                                 {:progressToken progress-token,
                                  :progress progress,
                                  :total total})))

;; [ref: resource_list_changed_notification]
(defn notify-resource-list-changed!
  "Notify the client that the list of resources has changed."
  [server]
  (lsp.server/send-notification server
                                "notifications/resources/list_changed"
                                {}))

;; [ref: resource_updated_notification]
(defn notify-resource-updated!
  "Notify the client that a specific resource has been updated."
  [server uri]
  (lsp.server/send-notification server
                                "notifications/resources/updated"
                                {:uri uri}))

;; [ref: prompt_list_changed_notification]
(defn notify-prompt-list-changed!
  "Notify the client that the list of prompts has changed."
  [server]
  (lsp.server/send-notification server
                                "notifications/prompts/list_changed"
                                {}))

;; [ref: tool_list_changed_notification]
(defn notify-tool-list-changed!
  "Notify the client that the list of tools has changed."
  [server]
  (lsp.server/send-notification server
                                "notifications/tools/list_changed"
                                {}))

;; [ref: logging_message_notification]
(def ^:private log-level-severity
  {"debug" 0, "info" 1, "notice" 2, "warning" 3,
   "error" 4, "critical" 5, "alert" 6, "emergency" 7})

(defn notify-log-message!
  "Send a logging message to the client.
   When context is provided, respects the log level set by the client
   via logging/setLevel — messages below the threshold are suppressed."
  [server level data & {:keys [logger context]}]
  (let [threshold (when context @(:log-level context))
        should-send? (or (nil? threshold)
                         (>= (log-level-severity level 0)
                             (log-level-severity threshold 0)))]
    (when should-send?
      (lsp.server/send-notification server
                                    "notifications/message"
                                    (cond-> {:level level, :data data}
                                      logger (assoc :logger logger))))))

;;; Roots
;; [ref: roots_support]
;; The server can request a list of root URIs from the client using roots/list.
;; The client can notify the server of changes via notifications/roots/list_changed.

(defn request-roots-list!
  "Send a roots/list request to the client. Returns the list of roots.
   The server must be passed as the first argument."
  [server]
  (let [pending-request (lsp.server/send-request server "roots/list" {})]
    (:roots (lsp.server/deref-or-cancel pending-request 30000 ::timeout))))

(defn refresh-roots!
  "Fetch the latest roots from the client and update the context."
  [server context]
  (let [roots (request-roots-list! server)]
    (when (not= ::timeout roots)
      (log/trace :fn :refresh-roots! :roots roots)
      (reset! (:roots context) roots))
    roots))

;; [ref: root_list_changed_notification]
(defmethod lsp.server/receive-notification "notifications/roots/list_changed"
  [_ context params]
  (log/trace :fn :receive-notification
             :method "notifications/roots/list_changed"
             :params params)
  (conform-or-log ::specs/root-list-changed-notification params)
  ;; When the client notifies that roots have changed, refresh them.
  ;; The server reference must be stored in context under :protocol.
  (when-let [server @(:protocol context)]
    (async/thread (refresh-roots! server context))))

;;; Sampling
;; [ref: sampling_create_message]
;; The server can request the client to sample an LLM.

(defn request-sampling!
  "Send a sampling/createMessage request to the client.
   params should contain:
   - :messages - vector of sampling messages [{:role \"user\" :content {...}}]
   - :maxTokens - maximum tokens to sample
   Optional:
   - :modelPreferences - model selection preferences
   - :systemPrompt - system prompt string
   - :includeContext - \"none\", \"thisServer\", or \"allServers\"
   - :temperature - sampling temperature
   - :stopSequences - vector of stop sequence strings
   - :metadata - provider-specific metadata
   Returns the client's sampling response or nil on timeout."
  [server params]
  (let [pending (lsp.server/send-request server "sampling/createMessage" params)]
    (lsp.server/deref-or-cancel pending 60000 nil)))

;;; Server Spec

(defn validate-spec!
  [server-spec]
  (when-not (specs/valid-server-spec? server-spec)
    (let [msg "Invalid server-spec definition"]
      (log/debug :msg msg :spec server-spec)
      (throw (ex-info msg (specs/explain-server-spec server-spec)))))
  server-spec)

(defn register-tool!
  "Register a tool with the server context. tool is a map with :name, :description,
   :inputSchema (and optionally :outputSchema). handler is (fn [arguments] response)."
  [context tool handler]
  (swap! (:tools context) assoc (:name tool) {:tool tool, :handler handler})
  (swap! (:capabilities context) assoc :tools {:listChanged true}))

(defn register-resource!
  "Register a resource with the server context. resource is a map with :uri, :name,
   :description, :mimeType. handler is (fn [uri] content-map)."
  [context resource handler]
  (swap! (:resources context) assoc
    (:uri resource)
    {:resource resource, :handler handler})
  (swap! (:capabilities context) assoc :resources {:subscribe true,
                                                    :listChanged true}))

(defn register-resource-template!
  "Register a resource template. template is a map with :uriTemplate, :name,
   :description, :mimeType. Templates describe URI patterns per RFC 6570."
  [context template]
  (swap! (:resource-templates context) assoc (:uriTemplate template) template)
  (swap! (:capabilities context) assoc :resources {:subscribe true,
                                                    :listChanged true}))

(defn register-completion!
  "Register a completion handler for a prompt or resource argument.
   ref-key is the prompt name or resource URI.
   arg-name is the argument name to complete.
   handler is (fn [partial-value] {:values [...] :total n :hasMore bool})."
  [context ref-key arg-name handler]
  (swap! (:completions context) assoc-in [ref-key arg-name] handler)
  (swap! (:capabilities context) assoc :completions {}))

(defn register-prompt!
  "Register a prompt with the server context. prompt is a map with :name,
   :description, :arguments. handler is (fn [arguments] {:messages [...]})."
  [context prompt handler]
  (swap! (:prompts context) assoc
    (:name prompt)
    {:prompt prompt, :handler handler})
  (swap! (:capabilities context) assoc :prompts {:listChanged true}))

(defn deregister-tool!
  "Remove a tool by name. Removes :tools capability when no tools remain."
  [context tool-name]
  (swap! (:tools context) dissoc tool-name)
  (when (empty? @(:tools context))
    (swap! (:capabilities context) dissoc :tools)))

(defn deregister-resource!
  "Remove a resource by URI. Removes :resources capability when no resources or templates remain."
  [context uri]
  (swap! (:resources context) dissoc uri)
  (when (and (empty? @(:resources context))
             (empty? @(:resource-templates context)))
    (swap! (:capabilities context) dissoc :resources)))

(defn deregister-prompt!
  "Remove a prompt by name. Removes :prompts capability when no prompts remain."
  [context prompt-name]
  (swap! (:prompts context) dissoc prompt-name)
  (when (empty? @(:prompts context))
    (swap! (:capabilities context) dissoc :prompts)))

(defn deregister-resource-template!
  "Remove a resource template by URI template. Removes :resources capability when
   no resources or templates remain."
  [context uri-template]
  (swap! (:resource-templates context) dissoc uri-template)
  (when (and (empty? @(:resources context))
             (empty? @(:resource-templates context)))
    (swap! (:capabilities context) dissoc :resources)))

(defn deregister-completion!
  "Remove a completion handler for a specific ref-key and arg-name. Removes
   :completions capability when no completion handlers remain."
  [context ref-key arg-name]
  (swap! (:completions context) update ref-key dissoc arg-name)
  (swap! (:completions context)
         (fn [m] (into {} (remove (fn [[_ v]] (empty? v))) m)))
  (when (empty? @(:completions context))
    (swap! (:capabilities context) dissoc :completions)))

(defn- create-empty-context
  [name version]
  (log/trace :fn :create-empty-context)
  ;; [tag: context_must_be_a_map]
  ;;
  ;; Since so much of the state is "global" in nature, it's tempting to
  ;; just make the entire context global instead of defining atoms at each
  ;; key. However, do not do this!
  ;;
  ;; This context is passed to lsp4j, which expects the data-structure to
  ;; be `associative?` in nature and uses it further for it's own temporary
  ;; state.
  {:server-info {:name name, :version version},
   :tools (atom {}),
   :resources (atom {}),
   :resource-templates (atom {}),
   :prompts (atom {}),
   :completions (atom {}),
   :subscriptions (atom {}),
   :roots (atom []),
   :log-level (atom nil),
   :protocol (atom nil),
   :protocol-version (atom nil),
   :capabilities (atom {:logging {}}),
   :connected-clients (atom {}),
   :cancelled-requests (atom #{})})

(defn create-context!
  "Create and configure an MCP server from a configuration map.
   Config map should have the shape:
   {:name \"server-name\"
    :version \"1.0.0\"
    :tools [{:name \"tool-name\"
             :description \"Tool description\"
             :inputSchema {...}
             :handler (fn [args] ...)}]
    :prompts [{:name \"prompt-name\"
               :description \"Prompt description\"
               :handler (fn [args] ...)}]
    :resources [{:uri \"resource-uri\"
                 :type \"text\"
                 :handler (fn [uri] ...)}]
    :instructions \"Optional instructions for LLMs on how to use this server\"}"
  [{:keys [name version tools prompts resources resource-templates instructions
            page-size],
    :as spec}]
  (validate-spec! spec)
  (log/with-context {:action :create-context!}
    (let [context (cond-> (create-empty-context name version)
                    instructions (assoc :instructions instructions)
                    page-size (assoc :page-size page-size))]
      (when (> (count tools) 0)
        (log/debug :num-tools (count tools)
                   :msg "Registering tools"
                   :server-info {:name name, :version version}))
      (doseq [tool tools]
        (register-tool! context (dissoc tool :handler) (:handler tool)))
      (when (> (count resources) 0)
        (log/debug :num-resources (count resources)
                   :msg "Registering resources"
                   :server-info {:name name, :version version}))
      (doseq [resource resources]
        (register-resource! context
                            (dissoc resource :handler)
                            (:handler resource)))
      (when (> (count prompts) 0)
        (log/debug :num-prompts (count prompts)
                   :msg "Registering prompts"
                   :server-info {:name name, :version version}))
      (doseq [prompt prompts]
        (register-prompt! context (dissoc prompt :handler) (:handler prompt)))
      (doseq [tmpl resource-templates]
        (register-resource-template! context tmpl))
      ;; Set capabilities based on what was registered
      (reset! (:capabilities context)
              (cond-> {:logging {}}
                (seq @(:tools context))
                  (assoc :tools {:listChanged true})
                (or (seq @(:resources context))
                    (seq @(:resource-templates context)))
                  (assoc :resources {:subscribe true, :listChanged true})
                (seq @(:prompts context))
                  (assoc :prompts {:listChanged true})))
      context)))

(defn start!
  "Start the MCP server. Returns a promise that resolves when the server shuts down."
  [server context]
  (log/info :msg "[SERVER] Starting server...")
  (lsp.server/start server context))

(defn shutdown!
  "Gracefully shut down the MCP server."
  [server]
  (log/info :msg "[SERVER] Shutting down server...")
  (lsp.server/shutdown server))

(defn chan-server
  "Create a channel-based MCP server for testing. Returns a server with
   :input-ch and :output-ch for direct message passing."
  []
  (let [input-ch (async/chan 3)
        output-ch (async/chan 3)]
    (lsp.server/chan-server {:output-ch output-ch, :input-ch input-ch})))
