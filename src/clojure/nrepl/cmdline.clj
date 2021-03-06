(ns nrepl.cmdline
  "A proof-of-concept command-line client for nREPL.  Please see
  e.g. REPL-y for a proper command-line nREPL client @
  https://github.com/trptcolin/reply/"
  {:author "Chas Emerick"}
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [nrepl.config :as config]
   [nrepl.core :as nrepl]
   [nrepl.ack :refer [send-ack]]
   [nrepl.server :refer [start-server]]
   [nrepl.transport :as transport]))

(def colored-output
  {:err #(binding [*out* *err*]
           (print "\033[31m")
           (print %)
           (print "\033[m")
           (flush))
   :out print
   :value (fn [x]
            (print "\033[34m")
            (print x)
            (println "\033[m")
            (flush))})

(defn- run-repl
  ([host port]
   (run-repl host port nil))
  ([host port {:keys [prompt err out value]
               :or {prompt #(print (str % "=> "))
                    err print
                    out print
                    value println}}]
   (let [transport (nrepl/connect :host host :port port)
         client (nrepl/client-session (nrepl/client transport Long/MAX_VALUE))
         ns (atom "user")]
     (println (format "nREPL %s" nrepl/version-string))
     (println (str "Clojure " (clojure-version)))
     (println (System/getProperty "java.vm.name") (System/getProperty "java.runtime.version"))
     (loop []
       (prompt @ns)
       (flush)
       (doseq [res (nrepl/message client {:op "eval" :code (pr-str (read))})]
         (when (:value res) (value (:value res)))
         (when (:out res) (out (:out res)))
         (when (:err res) (err (:err res)))
         (when (:ns res) (reset! ns (:ns res))))
       (recur)))))

(def #^{:private true} option-shorthands
  {"-i" "--interactive"
   "-r" "--repl"
   "-c" "--connect"
   "-b" "--bind"
   "-h" "--host"
   "-p" "--port"
   "-m" "--middleware"
   "-t" "--transport"
   "-n" "--handler"
   "-v" "--version"})

(def #^{:private true} unary-options
  #{"--interactive"
    "--connect"
    "--color"
    "--help"
    "--version"})

(defn- expand-shorthands
  "Expand shorthand options into their full forms."
  [args]
  (map (fn [arg] (or (option-shorthands arg) arg)) args))

(defn- keywordize-options [options]
  (reduce-kv
   #(assoc %1 (keyword (clojure.string/replace-first %2 "--" "")) %3)
   {}
   options))

(defn- split-args
  "Convert `args` into a map of options + a list of args.
  Unary options are set to true during this transformation.
  Returns a vector combining the map and the list."
  [args]
  (loop [[arg & rem-args :as args] args
         options {}]
    (if-not (and arg (re-matches #"-.*" arg))
      [options args]
      (if (unary-options arg)
        (recur rem-args
               (assoc options arg true))
        (recur (rest rem-args)
               (assoc options arg (first rem-args)))))))

(defn- display-help
  []
  (println "Usage:

  -i/--interactive            Start nREPL and connect to it with the built-in client.
  -c/--connect                Connect to a running nREPL with the built-in client.
  -C/--color                  Use colors to differentiate values from output in the REPL. Must be combined with --interactive.
  -b/--bind ADDR              Bind address, by default \"127.0.0.1\".
  -h/--host ADDR              Host address to connect to when using --connect. Defaults to \"127.0.0.1\".
  -p/--port PORT              Start nREPL on PORT. Defaults to 0 (random port) if not specified.
  --ack ACK-PORT              Acknowledge the port of this server to another nREPL server running on ACK-PORT.
  -n/--handler HANDLER        The nREPL message handler to use for each incoming connection; defaults to the result of `(nrepl.server/default-handler)`.
  -m/--middleware MIDDLEWARE  A sequence of vars, representing middleware you wish to mix in to the nREPL handler.
  -t/--transport TRANSPORT    The transport to use. By default that's nrepl.transport/bencode.
  --help                      Show this help message.
  -v/--version                Display the nREPL version."))

(defn- require-and-resolve
  "Require and resolve `thing`
  `thing` can be a string or a symbol."
  [thing]
  (let [thing (symbol thing)]
    (require (symbol (namespace thing)))
    (resolve thing)))

(def ^:private resolve-mw-xf
  (comp (map require-and-resolve)
        (keep identity)))

(defn- handle-seq-var
  [var]
  (let [x @var]
    (if (sequential? x)
      (into [] resolve-mw-xf x)
      [var])))

(def ^:private mw-xf
  (comp (map symbol)
        resolve-mw-xf
        (mapcat handle-seq-var)))

(defn- ->mw-list
  [middleware-var-strs]
  (into [] mw-xf middleware-var-strs))

(defn- build-handler
  "Build an nREPL handler from `middleware`.
  `middleware` is a sequence of vars or string which can be resolved
  to vars, representing middleware you wish to mix in to the nREPL
  handler. Vars can resolve to a sequence of vars, in which case
  they'll be flattened into the list of middleware."
  [middleware]
  (apply nrepl.server/default-handler (->mw-list middleware)))

(defn- url-scheme [transport]
  (if (= transport #'transport/tty)
    "telnet"
    "nrepl"))

(defn- ->int [x]
  (cond
    (nil? x) x
    (number? x) x
    :else (Integer/parseInt x)))

(defn- sanitize-middleware-option
  "Sanitize the middleware option.
  It can be either a string or a list, depending on whether it
  came from the command line or a config file.
  It can also be a vector of symbols or just a single symbol."
  [mw-opt]
  (cond
    (string? mw-opt)
    ;; some string like "[foo bar baz]" or "foo" from the command-line
    (let [mw (edn/read-string mw-opt)]
      (if (sequential? mw)
        mw
        [mw]))
    ;; symbol from a config file
    (symbol? mw-opt) [mw-opt]
    ;; nil or a vector from a config file
    :else mw-opt))

(defn -main
  [& args]
  (let [[options _args] (split-args (expand-shorthands args))
        options (keywordize-options options)
        options (merge config/config options)]
    ;; we have to check for --help first, as it's special
    (when (:help options)
      (display-help)
      (System/exit 0))
    (when (:version options)
      (println nrepl/version-string)
      (System/exit 0))
    ;; then we check for --connect
    (let [port (->int (:port options))
          host (:host options)]
      (when (:connect options)
        (run-repl host port)
        (System/exit 0))
      ;; otherwise we assume we have to start an nREPL server
      (let [bind (:bind options)
            ;; if some handler was explicitly passed we'll use it, otherwise we'll build one
            ;; from whatever was passed via --middleware
            handler (if (:handler options) (require-and-resolve (:handler options)))
            middleware (sanitize-middleware-option (:middleware options))
            handler (or handler (build-handler middleware))
            transport (if (:transport options) (require-and-resolve (:transport options)))
            greeting-fn (if (= transport #'transport/tty) #'transport/tty-greeting)
            server (start-server :port port :bind bind :handler handler
                                 :transport-fn transport :greeting-fn greeting-fn)]
        (when-let [ack-port (:ack options)]
          (binding [*out* *err*]
            (println (format "ack'ing my port %d to other server running on port %d"
                             (:port server) ack-port)
                     (:status (send-ack (:port server) ack-port)))))
        (let [port (:port server)
              ^java.net.ServerSocket ssocket (:server-socket server)
              host (.getHostName (.getInetAddress ssocket))]
          ;; The format here is important, as some tools (e.g. CIDER) parse the string
          ;; to extract from it the host and the port to connect to
          (println (format "nREPL server started on port %d on host %s - %s://%s:%d"
                           port host (url-scheme transport) host port))
          ;; Many clients look for this file to infer the port to connect to
          (let [port-file (io/file ".nrepl-port")]
            (.deleteOnExit port-file)
            (spit port-file port))
          (if (:interactive options)
            (run-repl host port (when (:color options) colored-output))
            ;; need to hold process open with a non-daemon thread -- this should end up being super-temporary
            (Thread/sleep Long/MAX_VALUE)))))))
