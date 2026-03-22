(ns io.modelcontext.clojure-sdk.stdio-client
  (:require [clojure.core.async :as async]
            [io.modelcontext.clojure-sdk.client :as client]
            [io.modelcontext.clojure-sdk.io-chan :as mcp.io-chan]
            [lsp4clj.server :as lsp.server]
            [me.vedang.logger.interface :as log])
  (:import (java.lang ProcessBuilder ProcessBuilder$Redirect)))

(set! *warn-on-reflection* true)

(defn- start-process!
  "Start a subprocess with the given command and return the Process.
   The process's stderr is redirected to the parent process's stderr."
  ^Process [command env]
  (let [pb (ProcessBuilder. ^java.util.List command)]
    (.redirectError pb ProcessBuilder$Redirect/INHERIT)
    (when env
      (let [env-map (.environment pb)]
        (doseq [[k v] env]
          (.put env-map (name k) (str v)))))
    (.start pb)))

(defn stdio-client
  "Create an MCP client that communicates with a server process via stdio.
   Returns a map with :client (the lsp4clj endpoint), :process (the subprocess),
   and :context (the client context).

   Options:
   - :command - vector of strings for the subprocess command (required)
   - :env - map of environment variables to set
   - :client-info - {:name \"..\" :version \"..\"} identifying this client
   - :roots - vector of root maps
   - :capabilities - client capabilities map
   - :sampling-handler - (fn [params] response) for sampling/createMessage requests
   - :on-tools-changed - (fn [params] ...) called when server's tool list changes
   - :on-resources-changed - (fn [params] ...) called when server's resource list changes
   - :on-prompts-changed - (fn [params] ...) called when server's prompt list changes
   - :on-resource-updated - (fn [params] ...) called when a subscribed resource updates
   - :on-log-message - (fn [params] ...) called when server sends a log message
   - :on-progress - (fn [params] ...) called when server sends progress notifications"
  [{:keys [command env] :as opts}]
  (log/trace :fn :stdio-client :command command)
  (let [process (start-process! command env)
        input-ch (mcp.io-chan/input-stream->input-chan (.getInputStream process))
        output-ch (mcp.io-chan/output-stream->output-chan (.getOutputStream process))
        log-ch (async/chan (async/sliding-buffer 20))
        endpoint (lsp.server/chan-server {:input-ch input-ch,
                                          :output-ch output-ch,
                                          :log-ch log-ch})
        context (client/create-context (dissoc opts :command :env))]
    {:client endpoint, :process process, :context context}))

(defn connect!
  "Start the client endpoint and initialize the connection.
   Stores server info and capabilities in the client context.
   Returns the server's initialize response."
  [{:keys [client context]}]
  (client/start! client context)
  (client/initialize! client {:context context
                              :client-info (:client-info context)
                              :capabilities (:capabilities context)}))

(defn disconnect!
  "Shut down the client and destroy the server process."
  [{:keys [client ^Process process]}]
  (client/shutdown! client)
  (when (and process (.isAlive process))
    (.destroy process)
    (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
    (when (.isAlive process)
      (.destroyForcibly process))))

(defmacro with-connection
  "Create and connect to an MCP server, execute body, then disconnect.
   Binds the connection map (with :client, :process, :context keys) to binding-sym.

   Example:
     (with-connection [conn {:command [\"java\" \"-cp\" \"server.jar\" \"my_server\"]
                             :client-info {:name \"my-client\" :version \"1.0.0\"}}]
       (client/list-tools! (:client conn)))"
  [[binding-sym opts] & body]
  `(let [~binding-sym (stdio-client ~opts)]
     (try
       (connect! ~binding-sym)
       ~@body
       (finally
         (disconnect! ~binding-sym)))))
