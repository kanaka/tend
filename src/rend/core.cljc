(ns rend.core
  (:require [clojure.data.codec.base64 :as base64]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.set :as set]

            [flatland.ordered.set :refer [ordered-set]]
            [com.rpl.specter :refer [setval transform ALL LAST MAP-VALS]]
            [clj-time.core :as ctime]
            [differ.core :as differ]

            [instacheck.core :as icore]
            [instacheck.reduce :as ireduce]

            [rend.util :as util]
            [rend.generator]
            [rend.image :as image]
            [rend.server]
            [rend.webdriver :as webdriver]
            ;;[wend.core :as wend]
            [wend.html-mangle :as html-mangle]
            [mend.parse]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test state management

(defn- elided-state*
  "Return a form of the test state that elides Clojure
  objects/functions, parsers, and the base weights."
  [state-val]
  (let [cnt-elide (fn [x] (keyword (str "ELIDED-" (count x))))]
    (->> state-val
         (setval    [:sessions MAP-VALS]   :ELIDED)
         (transform [:ws-clients]          cnt-elide)
         (setval    [:server]              :ELIDED)
         (setval    [:cleanup-fn]          :ELIDED)
         (transform [:cfg :weights :base]  cnt-elide)
         (setval    [:html-parser]         :ELIDED)
         (setval    [:css-parser]          :ELIDED))))

(defn serializable-state
  "Return a network serializable form of the test state that elides
  Clojure objects/functions, parsers, and the base weights."
  [state-val]
  (->> state-val
       elided-state*
       (transform [:test-slugs] #(into #{} %))))

(defn printable-state
  "Return a more printable form of the test state that elides
  everything that serializable-state does plus the log and the
  start weight list."
  [state-val]
  (let [cnt-elide (fn [x] (keyword (str "ELIDED-" (count x))))]
    (->> state-val
         elided-state*
         (transform [:weights]             cnt-elide)
         (setval    [:log MAP-VALS]        :ELIDED)
         (transform [:cfg :weights :start] cnt-elide))))

(defn new-test-run!
  "Update (mutate) state run number (to add 1) and return it"
  [state]
  (let [s (swap! state update-in [:run] #(+ 1 %))]
    (:run s)))

(defn new-test-iteration!
  "Update (mutate) state iteration number (to add 1) and return it"
  [state]
  (let [s (swap! state update-in [:iteration] #(+ 1 %))]
    (:iteration s)))

(defn new-test-seed!
  "Update (mutate) state seed value (to default if unset, otherwise add 1) and
  return it"
  [state]
  (let [s (swap! state #(let [{:keys [cfg current-seed]} %]
                          (assoc % :current-seed
                                 (if current-seed
                                   (+ 1 current-seed)
                                   (or (:start-seed cfg)
                                       (rand-int 1000000000))))))]
    (:current-seed s)))

(defn add-ws-client!
  [state ch req]
  ;;(println "new websocket client" ch)
  (swap! state update-in [:ws-clients] conj ch))

(defn remove-ws-client!
  [state ch status]
  ;;(println "lost websocket client" ch)
  (swap! state update-in [:ws-clients] disj ch))


(defn log!
  "Merges (mutates) log entry (map) into the current run and/or iter
  log position (depending on mode).

  The log structure looks like this:
     {:log {SLUG-0 ...
            SLUG-1 {:test-slug SLUG-1
                    :run       1
                    ...
                    :iter-log  {0 {:iter   0
                                   ...}]}]}}}"
  [state mode entry]

  ;; Update log both in memory and on disk
  (let [s (swap!
            state
            (fn [{:keys [test-slug run iteration] :as data}]
              (condp = mode
                :run (-> data
                         (update-in [:log test-slug]
                                    merge entry {:test-slug test-slug
                                                 :run run}))
                :iter (-> data
                          ;; Ensure that parent log always has :run key
                          (update-in [:log test-slug]
                                     merge {:test-slug test-slug
                                            :run run})
                          (update-in [:log test-slug :iter-log iteration]
                                     merge entry {:iter iteration})))))
        test-dir (str (-> (:cfg s) :web :dir) "/" (:test-slug s))]
    (spit (str test-dir "/log.edn")
          (get-in s [:log (:test-slug s)]))))


(defn update-state!
  "Update the test state by merging (shallow) new-state with the
  following special cases:
    - override :weights with :cfg :weights :fixed weights
    - decrement :current-seed so that it become the next seed"
  [state & [new-state]]
  (printable-state
    (swap! state #(let [wfixed (-> % :cfg :weights :fixed)
                        cur-weights (:weights %)
                        new-weights (:weights new-state)
                        next-seed (:current-seed new-state)
                        new-sessions (:sessions new-state)]
                    (merge
                      %
                      new-state
                      ;; Fixed weights always override
                      {:weights (merge cur-weights new-weights wfixed)}
                      ;; If a seed value is specified we
                      ;; decrement so that the next seed is the
                      ;; one specified
                      (when next-seed
                        {:current-seed (dec next-seed)}))))))

(defn reset-state!
  "Reset the test state by setting :run and :iteration to -1, emptying
  :sessions and :log, and then call update-state! with the
  new-state"
  [state & [new-state]]
  (update-state! state (merge {:run       -1
                               :iteration -1
                               :sessions  {}
                               :log       {}}
                              new-state)))

;; Watch state atom and keep report web page state and file report state
;; in sync.
(defn state-watcher
  "Keep the web page report and file report up-to-date based on the
  changes to the state atom. This includes notifying WebSocket clients
  so that browsers viewing the report page get live updates. New
  clients are sent the whole serializable state. After that clients
  are sent diffs between old-state and new-state."
  [_key _atom old-state new-state]
  (let [{:keys [ws-clients]} new-state
        new-ws-clients (set/difference (:ws-clients new-state)
                                       (:ws-clients old-state))
        cur-ws-clients (set/difference (:ws-clients new-state)
                                       new-ws-clients)]

    ;;(prn :state-watcher :new-ws-clients new-ws-clients :cur-ws-clients cur-ws-clients)

    (when (not (empty? new-ws-clients))
      ;; Limit to just the iter-log for the current slug
      (let [slug (:test-slug new-state)
            no-iters (setval [:log ALL LAST :iter-log ALL LAST] nil
                             (serializable-state new-state))
            data (assoc-in no-iters
                           [:log slug]
                           (get-in new-state [:log slug]))]
        (rend.server/ws-broadcast
          new-ws-clients {:msgType :full :data data})))

    (when (not (empty? cur-ws-clients))
      (let [data (differ/diff (serializable-state old-state)
                              (serializable-state new-state))]
        ;; If the serializable states differ (they may not since
        ;; changes may have been ELIDED), broadcast the delta to all
        ;; connected clients.
        (when (not= [{} {}] data)
          (rend.server/ws-broadcast
            cur-ws-clients {:msgType :patch :data data}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lower level testing functions (no state changes)
;; - these may affect file state but not test state atom

(defn check-page*
  "Utility function for check-page. See check-page."
  [sessions comp-method comp-thresh test-dir test-url iteration html-text]
  (let [path-prefix (str test-dir "/" iteration)
        url-prefix (str test-url "/" iteration)
        h-path (str path-prefix ".html")
        h-url (str url-prefix ".html")
        comp-alg (get-in image/compare-methods [comp-method :alg])
        comp-op  (get-in image/compare-methods [comp-method :op])
        comp-fn (fn [x] (comp-op comp-thresh x))
        d-path (str path-prefix "_diffs.edn")]
    (try
      (println "------")
      (spit h-path html-text)
      (spit (str h-path ".txt") html-text)

      ;; Load the page in each browser
      (println "Loading" h-url "in each browser")
      (doseq [[browser session] sessions]
        (webdriver/load-page session h-url))

      (Thread/sleep 1000)

      ;; - Screenshot each browser
      ;; - Bump up each image size to maximum dimension in each
      ;;   direction (replicating border color)
      ;; - Calculate an average result image and save it to disk
      ;; - Calculate a difference image and summary value for each
      ;;   image relative to the every other image.
      ;; - If any difference value is above a threshold, then that is
      ;;   a failed test
      (let [images (into
                     {}
                     (for [[browser-kw session] sessions]
                       (let [browser (name browser-kw)
                             ss-path (str path-prefix "_" browser ".png")
                             img (webdriver/screenshot-page session ss-path)]
                         [browser img])))
            imgs (apply assoc {}
                        (interleave (keys images)
                                    (image/normalize-images (vals images))))

            diffs (into
                    {}
                    (for [[browser img] imgs]
                      [browser
                       (into
                         {}
                         (for [[obrowser oimg] (dissoc imgs browser)]
                           [obrowser (image/diff img oimg comp-alg)]))]))
            ;; at least one difference is greater than threshold
            violation (seq (filter comp-fn (mapcat vals (vals diffs))))
            ;; every difference is greater than threshold
            violations (into {} (filter #(every? comp-fn (vals (val %)))
                                        diffs))]

        ; (println "Saving thumbnail for each screenshot")
        (doseq [[browser img] imgs]
          (let [thumb (image/thumbnail img)]
            (image/imwrite (str path-prefix "_" browser "_thumb.png") thumb)))

        ; (println "Saving difference values to" d-path)
        (spit d-path
              (pr-str
                (into {} (for [[b v] diffs]
                           [b (into {} (for [[bx t] v]
                                         [bx t]))]))))

        ; (println "Saving average picture")
        (let [avg (image/average (vals imgs))
              thumb (image/thumbnail avg)]
          (image/imwrite (str path-prefix "_avg.png") avg)
          (image/imwrite (str path-prefix "_avg_thumb.png") thumb))

        ; (println "Saving difference pictures")
        (doseq [[browser img] imgs]
          (doseq [[obrowser oimg] (dissoc imgs browser)]
            (let [pre1 (str path-prefix "_diff_" browser "_" obrowser)
                  pre2 (str path-prefix "_diff_" obrowser "_" browser)]
              (if (and (.exists (io/as-file
                                  (str pre2 ".png")))
                       (not (.exists (io/as-file
                                       (str pre1 ".png")))))
                (do
                  (sh "ln" "-sf"
                      (str (.getName (io/file pre2)) ".png")
                      (str pre1 ".png"))
                  (sh "ln" "-sf"
                      (str (.getName (io/file pre2)) "_thumb.png")
                      (str pre1 "_thumb.png")))
                (let [diff (image/absdiff img oimg)
                      thumb (image/thumbnail diff)]
                  (image/imwrite (str pre1 ".png") diff)
                  (image/imwrite (str pre1 "_thumb.png") thumb))))))

        ;; Do the actual check
        (when (not (empty? violations))
          (println "Threshold violations:" (map first violations)))

        [(not violation) diffs violations])
      (catch java.lang.ThreadDeath e
        (throw e))
      (catch Throwable t
        (prn :check-page-exception t)
        [false "Exception" nil]))))


;; Weight functions

(defn reduce-path-pick? [fixed-weights path]
  (if (or (contains? fixed-weights path)
          (#{:S :html :head :body
             :css-assignments :css-declaration} (first path))
          (and (= :element (first path))
               (>= (count path) 5)
               (= [:cat 3] (subvec path 2 4))))
    false
    true))

(defn wrap-reduce-wtrek
  "Parse the path weights based on the test case. A path is randomly
  chosen and reduce by half in the overall wtrek. The :weight-dist
  algorithm picks a path using a weighted random selection with the
  weighted probability based on the wtrek weight for the path
  multiplied by the distance into the grammar of the path from the
  root/start node. reduce-wtrek is called to propagate the reduction
  if needed.  Returns a wtrek subset of the weights that changed."
  [parser wtrek text mode pick-pred]
  (let [grammar (icore/parser->grammar parser)
        parsed-weights (:wtrek (icore/parse-wtrek parser text mode))
        final-wtrek (ireduce/reduce-wtrek-with-weights
                      grammar wtrek parsed-weights
                      {:pick-mode :weight
                       ;;:reduce-mode :max-child
                       :reduce-mode :reducer
                       :reducer-fn ireduce/reducer-half
                       :pick-pred pick-pred})]
    {:parsed-weights parsed-weights
     :final-wtrek final-wtrek}))

(defn parse-and-reduce
  "Does wrap-reduce-wtrek for both HTML and CSS part of the HTML test
  case.  Returns a wtrek subset of the weights that changed."
  [html-parser css-parser full-wtrek html pick-pred]
  (let [html-only (html-mangle/extract-html html)
        _ (prn :html-only html-only)
        css-only (html-mangle/extract-inline-css html)
        _ (prn :css-only css-only)
        red1 (wrap-reduce-wtrek html-parser full-wtrek
                                html-only :html pick-pred)
        red2 (wrap-reduce-wtrek css-parser (:final-wtrek red1)
                                css-only :css pick-pred)
        weight-adjusts (into {} (set/difference
                                  (set (:final-wtrek red2))
                                  (set full-wtrek)))]
    {:html-only html-only
     :css-only css-only
     :html-parsed-weights (:parsed-weights red1)
     :css-parsed-weights (:parsed-weights red2)
     :weight-adjusts weight-adjusts}))

(comment

(def hp (mend.parse/load-parser-from-grammar :html))
(def cp (mend.parse/load-parser-from-grammar :css))
(def hw (icore/wtrek (icore/parser->grammar hp)))
(def cw (icore/wtrek (icore/parser->grammar cp)))
(def w (merge hw cw))

(time (def new-w (parse-and-reduce hp cp w "<html><body>x<header>xx</header></body></html>")))

)


(defn summarize-weights
  "Return a summary of the tags, attributes, and properties that are
  represented by the weights."
  [html-grammar css-grammar weights]
  (reduce
    (fn [a [[base & _left :as p] _w]]
      (let [[grammar kind] (cond (= :element base)
                                 [html-grammar :tags]

                                 (re-seq #"-attribute" (name base))
                                 [html-grammar :attrs]

                                 (= :css-known-standard base)
                                 [css-grammar  :props])
            string (when grammar
                     (->> p
                          (icore/get-in-grammar grammar)
                          :parsers
                          first
                          :string))
            tok (when string
                  (string/replace string #"^<?([^=\"]*)[=\"]*$" "$1"))]
        (if (and tok
                 (not= [kind tok] [:attrs "style"]))
          (update-in a [kind] conj tok)
          a)))
    {:tags  #{}
     :attrs #{}
     :props #{}}
    (filter #(> (val %) 0) weights)))


;; TODO: do we really want base weights? It should be implicit in the
;; *_generators.clj code.
(defn load-weights
  "Load the base (multiple), start, and fixed weights paths. Returns
  map containing :base, :start, and :fixed."
  [base-paths fixed-path start-path]
  {:base  (apply merge
                 (map #(edn/read-string (slurp %)) base-paths))
   :start (when start-path (edn/read-string (slurp start-path)))
   :fixed (when fixed-path (edn/read-string (slurp fixed-path)))})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lower level testing functions (with state changes)

(defn qc-report
  [state test-slug report]
  (let [{:keys [cfg log weights html-parser css-parser iteration]} @state
        get-smallest #(or (get-in % [:shrunk :smallest 0])
                          (get-in % [:shrinking :smallest 0]))
        html-grammar (icore/parser->grammar html-parser)
        css-grammar (icore/parser->grammar css-parser)
        prev-html (get-smallest (get log test-slug))
        cur-html (get-smallest report)
        ;; Remove property object
        r (dissoc report :property)
        ;; If there is a new smallest test case then track iter of
        ;; smallest test case and add weight adjustment info
        r (if (not= prev-html cur-html)
            (let [weights-full (merge (-> cfg :weights :base) weights)
                  weights-fixed (-> cfg :weights :fixed)
                  red (parse-and-reduce html-parser
                                        css-parser
                                        weights-full
                                        cur-html
                                        (partial reduce-path-pick?
                                                 weights-fixed))
                  parsed-weights (merge-with +
                                             (:html-parsed-weights red)
                                             (:css-parsed-weights red))
                  summary (summarize-weights html-grammar
                                             css-grammar
                                             parsed-weights)]
              (when (> (:verbose cfg) 0)
                (println "parse-and-reduce :html-only"
                         (pr-str (:html-only red)))
                (println "parse-and-reduce :css-only"
                         (pr-str (:css-only red)))
                (println "qc-report :weight-adjusts"
                         (pr-str (:weight-adjusts red)))
                (println "qc-report :TAP-summary"
                         (pr-str :TAP-summary summary)))
              (merge r {:smallest-iter iteration
                        :weight-adjusts (:weight-adjusts red)
                        :TAP-summary summary}))
            r)]
    ;; Print status report
    (println "Report type:" (name (:type r)))
    (when (> (:verbose cfg) 1)
      (println "qc-report :report"
               (pr-str (assoc r :args :ELIDED :fail :ELIDED))))
    ;; Save the latest report
    (log! state :run r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test execution API

;; SIDE-EFFECTS: updates state atom :test-slugs, :iteration value, and
;; :iter log.
(defn check-page
  "Render and check a test case page. Then output a report file and
  send an update to any browsers currently viewing the page."
  [state extra-cfg test-slug html]
  (let [{:keys [cfg sessions]} @state
        cfg (merge (:cfg @state) extra-cfg)
        test-dir (str (-> cfg :web :dir) "/" test-slug)]

    ;; Create and communicate the test directories to the web server
    (io/make-parents (str test-dir "/foo"))
    ;; TODO: combine into a single update
    (update-state! state {:test-slug test-slug})
    (swap! state update-in [:test-slugs] conj test-slug)

    (let [test-iteration (new-test-iteration! state)
          host-port (str (get-in cfg [:web :host] "localhost")
                         ":" (get-in cfg [:web :port]))
          test-url (str "http://" host-port "/gen/" test-slug)
          comp-method (get-in cfg [:compare :method])
          comp-threshold (get-in cfg [:compare :threshold])
          [res diffs violations] (check-page* sessions
                                              comp-method
                                              comp-threshold
                                              test-dir
                                              test-url
                                              test-iteration
                                              html)
          log-entry {:html html
                     :result res
                     :diffs diffs
                     :violations violations}
          state-val (log! state :iter log-entry)]

      res)))


;; SIDE-EFFECTS: updates state atom run value, seed value, iteration
;; value, run log, and weights
(defn run-iterations
  "Run the quick check process against the test-state and update the
  test-state progressively as the checking happens. If the test mode
  is set to \"reduce-weights\" then the resulting shrunk test case
  will be used to adjust/reduce the weights in the test state for
  subsequent runs."
  [test-state extra-cfg]
  (let [{:keys [cfg test-id weights]} @test-state
        cfg (util/deep-merge (-> @test-state :cfg) extra-cfg) ;; add extra-cfg
        run (new-test-run! test-state)
        current-seed (new-test-seed! test-state)
        test-slug (str test-id "-" run "-" current-seed)
        test-dir (str (-> cfg :web :dir) "/" test-slug)]
    (update-state! test-state {:iteration -1})

    (println "Test State:")
    (pprint (assoc (printable-state @test-state) :cfg :ELIDED))

    (icore/save-weights (str test-dir "/weights-start.edn") weights)

    (let [;; Config and state specific versions of the
          ;; functions/generators used for the actual check process
          gen-html-fn (rend.generator/get-html-generator weights)
          check-fn (partial check-page test-state extra-cfg test-slug)
          report-fn (partial qc-report test-state test-slug)

          start-time (ctime/now)
          ;; ****** QuickCheck ******
          qc-res (icore/instacheck check-fn
                                   gen-html-fn
                                   (merge (:quick-check cfg)
                                          {:seed current-seed
                                           :report-fn report-fn}))
          end-time (ctime/now)
          qc-res (merge qc-res
                        {:current-seed current-seed
                         :start-time (.toDate start-time)
                         :end-time (.toDate end-time)
                         :elapsed-ms (ctime/in-millis
                                       (ctime/interval start-time end-time))})]
      ;; Merge the quick check results into the run log
      (log! test-state :run qc-res)

      ;; If requested, adjust weights for next run
      (when (= "reduce-weights" (-> cfg :test :mode))
        (when (> (:verbose cfg) 0)
            (println "Final weight-adjusts"
                     (pr-str (get-in @test-state
                                     [:log test-slug :weight-adjusts]))))
        (let [new-weights (merge weights
                                 (get-in @test-state
                                         [:log test-slug :weight-adjusts]))]
          (update-state! test-state {:weights new-weights})))
      (println "------")
      (println (str "Quick check results:"))
      (pprint qc-res)
      (println "------")
      (icore/save-weights (str test-dir "/weights-end.edn")
                          (:weights @test-state))
      (let [test-id-path (str (-> cfg :web :dir) "/" test-id ".edn")]
        (println (str "Final test state: " test-id-path))
        ;; Convert ordered set to regular set (which can be loaded
        ;; on-demand from slug log.edn files) and remove iter log data
        (spit test-id-path
              (setval [:log ALL LAST :iter-log ALL LAST] nil
                      (serializable-state @test-state))))
      qc-res)))

;; ---

;; SIDE-EFFECTS: updates state atom run value
(defn run-tests
  "Calls run-iterations the number of times specified in the
  configuration (-> cfg :test :runs)."
  [test-state extra-cfg]
  (let [cfg (util/deep-merge (-> @test-state :cfg) extra-cfg)] ;; add extra-cfg
    ;;(update-state! test-state {:run -1})
    ;; Do the test runs and report
    (doseq [run-idx (range (-> cfg :test :runs))]
      (println "-------------------------------------------------------------")
      (println (str "------ Run index " (inc run-idx) " --------------------"))
      (run-iterations test-state extra-cfg))))

;; ---

(defn cleanup-tester
  "Close webdriver browser sessions and websocket browser clients on
  exit"
  [state]
  (let [{:keys [ws-clients server sessions]} @state]
    (when (not (empty? ws-clients))
      (println "Closing WebSocket browser clients")
      (doseq [ch ws-clients]
        (rend.server/ws-close ch)))
    (when server
      (println "Stopping web server")
      (server :timeout 100))
    (when (not (empty? sessions))
      (println "Cleaning up browser sessions")
      (doseq [[browser session] sessions]
        (println "Stopping browser session for:" browser)
        (try
          (webdriver/stop-session session)
          (swap! state update-in [:sessions] dissoc browser)
          (catch Throwable e
            (println "Failed to stop browser session:" e)))))))

(defn init-tester
  "Takes a configuration map (usually loaded from a config.yaml file)
  and returns an initialized the test state atom:
    - Starts a web server for testing and reporting.
    - Loads the html and css parser
    - Makes a webdriver connection to each browser listed in config.
    - Generates a cleanup functions to tear down browser connections.
  Returns a map containing the config, references the above stateful
  objects, and other settings that are updates as testing progresses."
  [user-cfg & [extra-cfg]]
  (let [user-cfg (util/deep-merge user-cfg extra-cfg) ;; add extra-cfg
        ;; Replace weight paths with loaded weight maps
        weights-map (let [w (-> user-cfg :weights)]
                      (load-weights (:base w) (:fixed w) (:start w)))
        cfg (assoc user-cfg :weights weights-map)
        ;; ws-clients needs to be here early in case client connect
        ;; while parsers are being loaded
        test-state (atom {:ws-clients  #{}})
        ;; Start a web server for the test cases and reporting
        server (do
                 (io/make-parents (str (-> cfg :web :dir) "/foo"))
                 (rend.server/start-server
                   (-> cfg :web :port)
                   (-> cfg :web :dir)
                   (partial #'add-ws-client! test-state)
                   (partial #'remove-ws-client! test-state)))

        ;; We use the full parse versions of the grammar because we
        ;; pass the HTML through hickory which can modify it in
        ;; someways that make the gen parser unhappy.
        _ (println "Loading HTML parser")
        html-parser (mend.parse/load-parser-from-grammar :html :parse)
        _ (println "Loading CSS parser")
        css-parser (mend.parse/load-parser-from-grammar :css :parse-inline)

        ;; get the smallest possible test case
        test-start-value (rend.generator/html-start-value
                           (rend.generator/get-html-generator {}))

        cleanup-fn (partial cleanup-tester test-state)]

    (reset-state! test-state {:cfg              cfg
                              :test-slugs       (ordered-set)
                              :test-id          (rand-int 100000)
                              :server           server
                              :html-parser      html-parser
                              :css-parser       css-parser
                              :test-start-value test-start-value
                              :sessions         {}
                              ;; User specified weights (no base)
                              :weights          (:start weights-map)
                              :cleanup-fn       cleanup-fn})

    ;; Attach watcher function to state atom to send updates to any
    ;; listening browser websocket clients
    (add-watch test-state :state-watcher #'state-watcher)

    ;; On Ctrl-C cleanup browser sessions we are about to create
    (.addShutdownHook (Runtime/getRuntime) (Thread. cleanup-fn))

    ;; Create webdriver/selenium sessions to the testing browsers
    ;; TODO: check that all browser IDs are unique
    (try
      (doseq [[browser {:keys [url capabilities]}] (:browsers cfg)]
        (println "Initializing browser session for:" browser)
        (swap! test-state update-in [:sessions] assoc browser
               (webdriver/init-session url (or capabilities {}))))
      test-state
      (catch Exception e
        (cleanup-fn)
        (throw e)))))

(comment

(require '[clojure.test.check.generators :as gen])
(def weights-map (load-weights ["resources/html5-weights.edn"
                                "resources/css3-weights.edn"]
                               "resources/fixed-weights.edn" nil))

(def gen-html-fn (rend.generator/get-html-generator
                   (merge (:start weights-map) (:fixed weights-map))))

(doseq [s (gen/sample gen-html-fn)] (println s))

(first (drop 19 (gen/sample-seq gen-html-fn 20)))

)

(comment

(require '[clj-yaml.core :as yaml])
(def file-cfg (yaml/parse-string (slurp "config-dev.yaml")))

(time (def test-state (init-tester file-cfg {:verbose 1})))

(time (def res (run-tests test-state {:test {:runs 2} :quick-check {:iterations 10}})))
  ;; OR
(def res (run-iterations test-state {:quick-check {:iterations 3}}))
  ;; OR
(check-page test-state {} "test1" "<html><link rel=\"stylesheet\" href=\"/static/normalize.css\"><link rel=\"stylesheet\" href=\"/static/rend.css\"><body>hello</body></html>")
  ;; OR
(check-page test-state {} "test1" "<html><link rel=\"stylesheet\" href=\"/static/normalize.css\"><link rel=\"stylesheet\" href=\"/static/rend.css\"><body>hello<button></button></body></html>")

;; Do not use :reload-all or it will break ring/rend.server
(require 'wend.core 'rend.core :reload)

)

(comment

;; TODO: make this callable in wend
(require '[instacheck.reduce :as r] '[instacheck.grammar :as g])
(def hp (mend.parse/load-parser-from-grammar :html))
(def hg (g/parser->grammar hp))
(def cp (mend.parse/load-parser-from-grammar :css))
(def cg (g/parser->grammar cp))
(def w (read-string (slurp "tmp/smedberg.edn")))

(spit "/tmp/hg.grammar" (g/grammar->ebnf (sort-by (comp str key) hg)))
(def hg2 (r/prune-grammar hg {:wtrek w}))
(spit "/tmp/hg2.grammar" (g/grammar->ebnf (sort-by (comp str key) hg2)))

(spit "/tmp/cg.grammar" (g/grammar->ebnf (sort-by (comp str key) cg)))
(def cg2 (r/prune-grammar cg {:wtrek w}))
(spit "/tmp/cg2.grammar" (g/grammar->ebnf (sort-by (comp str key) cg2)))

;;(def weights-map (load-weights ["resources/html5-weights.edn" "resources/css3-weights.edn"] "resources/fixed-weights.edn" "tmp/smedberg.edn"))
;;(def w (merge (:start weights-map) (:fixed weights-map)))
;;(time (def ctx (r/reduce-weights hg (merge (g/wtrek hg 0) w))))
;;(def hg3 (r/prune-grammar hg {:wtrek (:wtrek ctx) :removed (:removed ctx)}))
;;(spit "/tmp/hg3.grammar" (g/grammar->ebnf (sort-by (comp str key) hg3)))

)

(comment

(defn get-summary [state kind]
  (reduce
    (fn [res [item data]]
      (if (contains? res item)
        (assoc res item (conj (get res item) data))
        (assoc res item [data])))
    {}
    (for [[slug data] (:log state)
          item (get-in data [:TAP-summary kind])]
      [item {:slug slug 
             :smallest-iter (:smallest-iter data)
             :smallest (-> data :shrunk :smallest)}])))

(defn get-new-weights [state]
  (let [w (:weights state)
        wf (-> state :cfg :weights :fixed)]
    (select-keys w (set/difference (set (keys w)) (set (keys wf))))))

)

