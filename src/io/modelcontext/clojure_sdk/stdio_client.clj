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
   - :capabilities - client capabilities map"
  [{:keys [command env client-info roots capabilities]}]
  (log/trace :fn :stdio-client :command command)
  (let [process (start-process! command env)
        input-ch (mcp.io-chan/input-stream->input-chan (.getInputStream process))
        output-ch (mcp.io-chan/output-stream->output-chan (.getOutputStream process))
        log-ch (async/chan (async/sliding-buffer 20))
        endpoint (lsp.server/chan-server {:input-ch input-ch,
                                          :output-ch output-ch,
                                          :log-ch log-ch})
        context (client/create-context {:client-info client-info,
                                        :roots roots,
                                        :capabilities capabilities})]
    {:client endpoint, :process process, :context context}))

(defn connect!
  "Start the client endpoint and initialize the connection.
   Returns the server's initialize response."
  [{:keys [client context]}]
  (client/start! client context)
  (client/initialize! client))

(defn disconnect!
  "Shut down the client and destroy the server process."
  [{:keys [client ^Process process]}]
  (client/shutdown! client)
  (when (and process (.isAlive process))
    (.destroy process)
    (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
    (when (.isAlive process)
      (.destroyForcibly process))))
