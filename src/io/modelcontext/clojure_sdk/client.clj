(ns io.modelcontext.clojure-sdk.client
  (:require [io.modelcontext.clojure-sdk.specs :as specs]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log]))

;;; Client Context

(defn create-context
  "Create a client context for an MCP client.
   opts may contain:
   - :client-info - {:name \"..\" :version \"..\"} identifying this client
   - :roots - vector of root maps [{:uri \"file://..\" :name \"..\"} ...]
   - :capabilities - client capabilities map
   - :sampling-handler - (fn [params] response) for sampling/createMessage requests"
  [{:keys [client-info roots capabilities sampling-handler]}]
  {:client-info (or client-info {:name "mcp-clojure-client", :version "1.0.0"}),
   :roots (atom (or roots [])),
   :capabilities (or capabilities
                     (cond-> {:roots {:listChanged true}}
                       sampling-handler (assoc :sampling {}))),
   :sampling-handler sampling-handler,
   :server-info (atom nil),
   :server-capabilities (atom nil)})

;;; Client-side request handlers
;; These handle requests that the SERVER sends TO the client.

;; The server may request the client's root list
(defmethod lsp.server/receive-request "roots/list"
  [_ context _params]
  (log/trace :fn :receive-request :method "roots/list")
  {:roots @(:roots context)})

;; [ref: sampling_create_message]
;; The server may request the client to sample an LLM
(defmethod lsp.server/receive-request "sampling/createMessage"
  [_ context params]
  (log/trace :fn :receive-request
             :method "sampling/createMessage"
             :params params)
  (if-let [handler (:sampling-handler context)]
    (handler params)
    (do (log/error :fn :receive-request
                   :method "sampling/createMessage"
                   :error "No sampling handler registered")
        {:error {:code -1, :message "Sampling not supported by this client"}})))

;;; Client-side notification handlers
;; These handle notifications that the SERVER sends TO the client.

;; [ref: tool_list_changed_notification]
(defmethod lsp.server/receive-notification "notifications/tools/list_changed"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/tools/list_changed"
             :params params))

;; [ref: resource_list_changed_notification]
(defmethod lsp.server/receive-notification "notifications/resources/list_changed"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/resources/list_changed"
             :params params))

;; [ref: resource_updated_notification]
(defmethod lsp.server/receive-notification "notifications/resources/updated"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/resources/updated"
             :params params))

;; [ref: prompt_list_changed_notification]
(defmethod lsp.server/receive-notification "notifications/prompts/list_changed"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/prompts/list_changed"
             :params params))

;; [ref: logging_message_notification]
(defmethod lsp.server/receive-notification "notifications/message"
  [_ _context params]
  (log/trace :fn :receive-notification
             :method "notifications/message"
             :params params))

;;; Client API — Requests (sent TO server)

(def ^:private default-timeout-ms 30000)

(defn start!
  "Start the client endpoint."
  [client context]
  (lsp.server/start client context))

(defn initialize!
  "Initialize the connection with an MCP server.
   Sends initialize request followed by initialized notification.
   Returns the server's initialize response."
  ([client] (initialize! client {}))
  ([client {:keys [client-info capabilities protocol-version context]
            :or {client-info {:name "mcp-clojure-client", :version "1.0.0"},
                 capabilities {:roots {:listChanged true}},
                 protocol-version (first specs/supported-protocol-versions)}}]
   (let [params {:protocolVersion protocol-version,
                 :capabilities capabilities,
                 :clientInfo client-info}
         pending (lsp.server/send-request client "initialize" params)
         result (lsp.server/deref-or-cancel pending default-timeout-ms nil)]
     (when result
       (lsp.server/send-notification client "notifications/initialized" {})
       (when context
         (reset! (:server-info context) (:serverInfo result))
         (reset! (:server-capabilities context) (:capabilities result)))
       (log/trace :fn :initialize!
                  :server-info (:serverInfo result)
                  :protocol-version (:protocolVersion result)))
     result)))

(defn ping!
  "Ping the server. Returns the response or nil on timeout."
  [client]
  (let [pending (lsp.server/send-request client "ping" {})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

(defn list-tools!
  "List available tools from the server."
  ([client] (list-tools! client {}))
  ([client params]
   (let [pending (lsp.server/send-request client "tools/list" params)]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn call-tool!
  "Call a tool on the server."
  ([client tool-name] (call-tool! client tool-name {}))
  ([client tool-name arguments]
   (let [pending (lsp.server/send-request client "tools/call"
                                          {:name tool-name,
                                           :arguments arguments})]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn list-resources!
  "List available resources from the server."
  ([client] (list-resources! client {}))
  ([client params]
   (let [pending (lsp.server/send-request client "resources/list" params)]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn read-resource!
  "Read a resource from the server by URI."
  [client uri]
  (let [pending (lsp.server/send-request client "resources/read" {:uri uri})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

(defn list-resource-templates!
  "List available resource templates from the server."
  ([client] (list-resource-templates! client {}))
  ([client params]
   (let [pending (lsp.server/send-request client "resources/templates/list" params)]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn subscribe-resource!
  "Subscribe to updates for a resource URI."
  [client uri]
  (let [pending (lsp.server/send-request client "resources/subscribe" {:uri uri})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

(defn unsubscribe-resource!
  "Unsubscribe from updates for a resource URI."
  [client uri]
  (let [pending (lsp.server/send-request client "resources/unsubscribe"
                                         {:uri uri})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

(defn list-prompts!
  "List available prompts from the server."
  ([client] (list-prompts! client {}))
  ([client params]
   (let [pending (lsp.server/send-request client "prompts/list" params)]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn get-prompt!
  "Get a specific prompt from the server."
  ([client prompt-name] (get-prompt! client prompt-name {}))
  ([client prompt-name arguments]
   (let [pending (lsp.server/send-request client "prompts/get"
                                          {:name prompt-name,
                                           :arguments arguments})]
     (lsp.server/deref-or-cancel pending default-timeout-ms nil))))

(defn complete!
  "Request autocompletion suggestions from the server."
  [client ref argument]
  (let [pending (lsp.server/send-request client "completion/complete"
                                         {:ref ref, :argument argument})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

(defn set-logging-level!
  "Set the server's logging level."
  [client level]
  (let [pending (lsp.server/send-request client "logging/setLevel" {:level level})]
    (lsp.server/deref-or-cancel pending default-timeout-ms nil)))

;;; Pagination Helper

(defn list-all!
  "Collect all items from a paginated list endpoint by following cursors.
   list-fn should be a function that accepts [client params] and returns
   a paginated result. items-key is the key containing the items
   (e.g., :tools, :resources, :prompts, :resourceTemplates).
   Returns a flat vector of all items."
  [client list-fn items-key]
  (loop [cursor nil
         acc []]
    (let [result (list-fn client (cond-> {}
                                   cursor (assoc :cursor cursor)))
          new-acc (into acc (get result items-key))]
      (if-let [next-cursor (:nextCursor result)]
        (recur next-cursor new-acc)
        new-acc))))

(defn list-all-tools!
  "List all tools from the server, automatically following pagination cursors."
  [client]
  (list-all! client list-tools! :tools))

(defn list-all-resources!
  "List all resources from the server, automatically following pagination cursors."
  [client]
  (list-all! client list-resources! :resources))

(defn list-all-prompts!
  "List all prompts from the server, automatically following pagination cursors."
  [client]
  (list-all! client list-prompts! :prompts))

(defn list-all-resource-templates!
  "List all resource templates from the server, automatically following pagination cursors."
  [client]
  (list-all! client list-resource-templates! :resourceTemplates))

;;; Client Notifications (sent TO server)

(defn notify-roots-changed!
  "Notify the server that the client's root list has changed."
  [client]
  (lsp.server/send-notification client "notifications/roots/list_changed" {}))

(defn shutdown!
  "Gracefully shut down the MCP client."
  [client]
  (lsp.server/shutdown client))
