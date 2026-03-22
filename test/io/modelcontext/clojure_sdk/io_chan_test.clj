(ns io.modelcontext.clojure-sdk.io-chan-test
  "Unit tests for the IO channel transport layer — JSON serialization,
   key transformation, and stream-to-channel conversion."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [io.modelcontext.clojure-sdk.io-chan :as io-chan])
  (:import (java.io PipedInputStream PipedOutputStream)))

(deftest input-stream-reads-json-lines
  (testing "input-stream->input-chan reads newline-delimited JSON from a stream"
    (let [out (PipedOutputStream.)
          in (PipedInputStream. out)
          ch (io-chan/input-stream->input-chan in)]
      ;; Write two JSON messages
      (.write out (.getBytes "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n"))
      (.write out (.getBytes "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"))
      (.flush out)
      ;; Read them back from the channel
      (let [msg1 (async/<!! ch)
            msg2 (async/<!! ch)]
        (is (= "2.0" (:jsonrpc msg1)))
        (is (= "ping" (:method msg1)))
        (is (= 1 (:id msg2)))
        (is (= "tools/list" (:method msg2))))
      ;; Close the output to trigger EOF
      (.close out)
      ;; Channel should close after EOF
      (is (nil? (async/<!! ch))))))

(deftest output-stream-writes-json-lines
  (testing "output-stream->output-chan writes JSON lines to a stream"
    (let [out (PipedOutputStream.)
          in (PipedInputStream. out)
          ch (io-chan/output-stream->output-chan out)]
      ;; Put a message on the channel
      (async/>!! ch {:jsonrpc "2.0" :id 1 :result {:tools []}})
      ;; Read the written line
      (Thread/sleep 100)
      (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in))
            line (.readLine reader)]
        (is (some? line))
        ;; Should be valid JSON with camelCase keys
        (is (.contains ^String line "jsonrpc"))
        (is (.contains ^String line "2.0")))
      (async/close! ch))))

(deftest underscore-preservation-in-output
  (testing "Output serialization preserves leading underscores like _meta"
    (let [out (PipedOutputStream.)
          in (PipedInputStream. out)
          ch (io-chan/output-stream->output-chan out)]
      (async/>!! ch {:jsonrpc "2.0"
                     :id 1
                     :method "tools/call"
                     :params {:name "test"
                              :_meta {:progressToken "tok-1"}}})
      (Thread/sleep 100)
      (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in))
            line (.readLine reader)]
        ;; _meta should be preserved as "_meta", not "meta"
        (is (.contains ^String line "_meta"))
        ;; progressToken should be camelCase
        (is (.contains ^String line "progressToken")))
      (async/close! ch))))

(deftest camel-case-key-transformation
  (testing "Keyword keys are converted to camelCase in output"
    (let [out (PipedOutputStream.)
          in (PipedInputStream. out)
          ch (io-chan/output-stream->output-chan out)]
      (async/>!! ch {:jsonrpc "2.0"
                     :id 1
                     :result {:serverInfo {:name "test" :version "1.0"}
                              :protocolVersion "2024-11-05"
                              :capabilities {:listChanged true}}})
      (Thread/sleep 100)
      (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in))
            line (.readLine reader)]
        (is (.contains ^String line "serverInfo"))
        (is (.contains ^String line "protocolVersion"))
        (is (.contains ^String line "listChanged")))
      (async/close! ch))))

(deftest input-stream-closes-on-eof
  (testing "Channel closes when input stream reaches EOF"
    (let [out (PipedOutputStream.)
          in (PipedInputStream. out)
          ch (io-chan/input-stream->input-chan in)]
      ;; Close immediately
      (.close out)
      ;; Channel should return nil (closed)
      (is (nil? (async/<!! ch))))))

(deftest roundtrip-through-piped-streams
  (testing "Data survives a write→read roundtrip through piped streams"
    (let [server-out (PipedOutputStream.)
          client-reads (PipedInputStream. server-out)
          write-ch (io-chan/output-stream->output-chan server-out)
          read-ch (io-chan/input-stream->input-chan client-reads)]
      ;; Write a message with various key types
      (async/>!! write-ch {:jsonrpc "2.0"
                           :id 42
                           :result {:serverInfo {:name "test-srv"}
                                    :capabilities {:tools {:listChanged true}}
                                    :protocolVersion "2024-11-05"}})
      ;; Read it back
      (let [msg (async/<!! read-ch)]
        (is (= "2.0" (:jsonrpc msg)))
        (is (= 42 (:id msg)))
        ;; Keys from babashka.json come back as keywords
        (is (some? (:result msg)))
        ;; The serverInfo key should be readable
        (is (some? (get-in msg [:result :serverInfo]))))
      (async/close! write-ch)
      ;; read-ch should close after write-ch closes
      (Thread/sleep 200)
      (is (nil? (async/<!! read-ch))))))
