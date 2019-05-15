(ns mend.cli
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.walk :refer [postwalk]]

            [com.rpl.specter :refer [nthpath]]

            [instacheck.core :as instacheck]
            [instacheck.cli :as instacheck-cli]
            [instacheck.grammar :as instacheck-grammar]
            [instacheck.codegen :as instacheck-codegen]
            [wend.core :as wend]

            ;; Not actually used here, but convenient for testing
            [clojure.pprint :refer [pprint]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck :as chuck]))

;; Find each :path in a grammar and replace it with :value
;; This allows us to replace stub generators in the grammar with
;; references to generators in a different namespace as just one
;; example.
(def html5-grammar-updates
  [;; Replace the stub CSS value generator with real one
   {:path [:attr-val-style]
    :value {:tag :nt :keyword :css-assignments}}
   ;; Replace the image generator
   {:path [:img-attribute :parsers (nthpath 6) :parsers (nthpath 1)]
    :value {:tag :nt :keyword :rgen/image-path}}])

(def css3-grammar-updates
  [;; Replace regex number generators with actual numeric/sized types
   {:path [:nonprop-integer]
    :value {:tag :nt :keyword :gen/int}}
   {:path [:nonprop-positive-integer]
    :value {:tag :nt :keyword :gen/pos-int}}
   {:path [:number-float]
    :value {:tag :nt :keyword :gen/double}}])

(defn prune-S [x]
  (if (and (:parsers x)
           (> (count (:parsers x)) 1))
    (assoc x :parsers (filter #(not= (:keyword %) :S)
                              (:parsers x)))
    x))

(defn css3-grammar-update-fn [ctx grammar]
  (let [g1 (instacheck/apply-grammar-updates grammar css3-grammar-updates)
        ;; Remove empty strings
        g2 (postwalk prune-S g1)]
    g2))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ns-prefix [ctx]
  (str
"(ns " (:namespace ctx) "
   (:require [clojure.test.check.generators :as gen]
             [com.gfredericks.test.chuck.generators :as chuck]
             [instacheck.generators :as igen]
             [rend.misc-generators :as rgen]))

;; Generated by mend.cli

"))


(defn grammar->ns
  [ctx grammar]
  (str (ns-prefix ctx)
       (if (:function ctx)
         (instacheck-codegen/grammar->generator-func-source ctx grammar)
         (instacheck-codegen/grammar->generator-defs-source ctx grammar))))

(defn pr-err
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

(defn opt-errors [opts]
  (when (:errors opts)
    (doall (map pr-err (:errors opts)))
    (System/exit 2))
  opts)

(def cli-options
  (vec
    (concat
      instacheck-cli/general-cli-options
      [[nil "--mode MODE"
        "Mode (html5 or css3) for grammar transforms."
        :validate [#(get #{"html5" "css3"} %) "Must be 'html5' or 'css3'"]]
       [nil "--clj-output CLJ-OUTPUT"
        "Write Clojure code to path."]
       [nil "--namespace NAMESPACE"
        "Name of namespace to generate"]
       [nil "--ebnf-input EBNF-INPUT"
        "EBNF file to load/parse"]
       [nil "--function FUNCTION"
        "Emit a function named FUNCTION which returns a generator rather than a defn for every generator"]])))

(defn ebnf->clj [opts]
  (let [grammar-update (condp = (:mode opts)
                         "html5" html5-grammar-updates
                         "css3" css3-grammar-update-fn)
        ctx (merge {:weights-res (atom {})
                    :grammar-updates grammar-update}
                   (select-keys opts [:namespace
                                      :weights :function]))

        ;; The following each take 4-6 seconds
        _ (println "Loading grammar from" (:ebnf-input opts))
        grammar (instacheck-grammar/load-grammar (slurp (:ebnf-input opts)))
        _ (println "Converting grammar to clojure generators")
        ns-str (grammar->ns ctx grammar)]

    (when-let [wfile (:weights-output opts)]
      (println "Saving weights to" wfile)
      (wend/save-weights wfile @(:weights-res ctx)))

    ns-str))

(defn -main
  "This takes about 10-30 seconds to run"
  [& args]
  (let [opts (:options (opt-errors (parse-opts args cli-options)))]
    (when (not (:mode opts))
      (println "--mode MODE (html5 or css3) is required")
      (System/exit 2))
    (when (not (:ebnf-input opts))
      (println "--ebnf-input EBNF-INPUT is required")
      (System/exit 2))
    (when (not (:clj-output opts))
      (println "--clj-output CLJ-OUTPUT is required")
      (System/exit 2))
    (when (not (:namespace opts))
      (println "--namespace NAMESPACE required")
      (System/exit 2))

    (println "Saving Clojure code to" (:clj-output opts))
    (spit (:clj-output opts) (ebnf->clj opts))))

