(ns mend.html
  (:require [clojure.set :refer [union]]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [mend.ebnf :as ebnf]
            [instaparse.core :as insta]

            [clojure.tools.cli :refer [parse-opts]]

            ;; Not actually used here, but convenient for testing
            [clojure.pprint :refer [pprint]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck :as chuck]))

;; https://developer.mozilla.org/en-US/docs/Web/HTML/Reference
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Element
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Attributes
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Global_attributes
;; - XML ebnf:
;;   http://bottlecaps.de/rr/ui
;;   https://www.w3.org/TR/2000/REC-xml-20001006
;; - HTML ebnf:
;;   https://github.com/lisp/de.setf.xml/blob/master/bnf/html-grammar.bnf
;; http://w3c.github.io/html/syntax.html#void-elements

(def HTML5-EBNF-BASE "data/html5-base.ebnf")
(def HTML5-EBNF-ATTR-VALS "data/html5-attr-vals.ebnf")

(def html5-elements (json/read-str (slurp "data/html5-elements.json")))
(def html5-attributes (json/read-str (slurp "data/html5-attributes.json")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pr-err
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

(defn global-attributes
  [attr-map]
  (keep #(when (= ["Global attribute"] (val %)) (key %)) attr-map))

;; (defn non-global-attributes
;;   [attr-map]
;;   (into {} (filter #(not= ["Global attribute"] (val %)) attr-map)))

(defn elem-attr-map
  "Takes an element map (map of elements to URL strings) and a map of
  attributes (attributes to set of elements that use those attributes)
  and returns a single map (elements to set of attributes valid for
  that element). Does not include global attributes."
  [elem-map attr-map]
  (let [e-elems (set (keys elem-map))
        a-elems (disj (set (flatten (vals attr-map))) "Global attribute")
        all-elems (union e-elems a-elems)
        base (into {} (for [e all-elems] [e []]))
        full (apply merge-with #(vec (concat %1 %2))
                    base
                    (for [[k vs] attr-map
                          v vs
                          :when (not= "Global attribute" v)]
                      {v [k]}))]
    full))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Void tags do not have a closing tag.
;; http://w3c.github.io/html/syntax.html#void-elements
(def void-tags #{"area" "base" "br" "col" "embed" "hr" "img" "input"
                 "link" "meta" "param" "source" "track" "wbr"})

;; Boolean only tags can appear without an assignment value
(def bool-attrs #{"async" "autofocus" "autoplay" "checked" "controls"
                  "defer" "disabled" "hidden" "ismap" "loop" "muted"})

;; Only appear once and in specific order
;; TODO: handle title
(def special-elems #{"html" "head" "title" "body"})

;; TODO: handle data-* global attribute
(def special-attrs #{"data-*"})


(defn ebnf-tag-rhs
  [tag-name]
  (if (void-tags tag-name)
    [;; void tag (no end-tag)
     (str "<'<'> '" tag-name "' "
          "(<space> " tag-name "-attribute)* "
          "<opt-space> <'>'> <opt-space>")]
    [;; empty tag (i.e. <tag ... />)
     (str "<'<'> '" tag-name "' "
          "(<space> " tag-name "-attribute)* "
          "<opt-space> <'/>'> <opt-space>")
     ;; full tag (i.e. <tag ...> ... </tag>)
     (str "<'<'> '" tag-name "' "
          "(<space> " tag-name "-attribute)* "
          "<opt-space> <'>'> (element | content)* "
          "<'</'> '" tag-name "' "
          "<opt-space> <'>'> <opt-space>")]))

(defn ebnf-element
  [tag-names]
  (let [lhs "element "
        pre "        "]
    (str
      lhs "= "
      (string/join
        (str "\n" pre "| ")
        (mapcat ebnf-tag-rhs tag-names)))))

(defn ebnf-tag-attrs
  [tag-name attrs & append]
  (let [lhs (str tag-name "-attribute ")
        pre (apply str (repeat (count lhs) " "))]
    (str
      lhs "= "
      (string/join
        (str "\n" pre "| ")
        (concat
          (for [a (sort attrs)]
            (if (bool-attrs a)
              (str
                "'" a "'")
              (str
                "'" a "=\"' attr-val-" a " '\"'")))
          append)))))

(defn ebnf-elements-attributes
  [elem-map attr-map]
  (let [attr-map (apply dissoc attr-map special-attrs)
        elems-attrs (elem-attr-map elem-map attr-map)
        elements (sort (keys (apply dissoc elems-attrs special-elems)))]
    (str
      (ebnf-element elements)
      "\n\n"
      (ebnf-tag-attrs "global" (global-attributes attr-map))
      "\n\n"
      (string/join
        "\n"
        (for [[t a] (sort elems-attrs)]
          (ebnf-tag-attrs t a "global-attribute"))))))

;; TODO: head should accept other things
(def ebnf-prefix
"(* This is generated by src/mend/html5.clj *)

html = <'<'> 'html' <opt-space> <'>'> head? body <'</'> 'html' <opt-space> <'>'> <opt-space>

head = <'<'> 'head' <opt-space> <'>'> title? <'</'> 'head' <opt-space> <'>'> <opt-space>

title = <'<'> 'title' (<space> title-attribute)* <opt-space> <'>'> content* <'</'> 'title' <opt-space> <'>'> <opt-space>

body = <'<'> 'body' (<space> body-attribute)* <opt-space> <'>'> (element | content)* <'</'> 'body' <opt-space> <'>'> <opt-space>


")

(defn ebnf-all
  "Takes an element map and an attribute map and return an EBNF
  grammar as a string that represents the full HTML5 EBNF syntax."
  [elem-map attr-map]
  (str
    ebnf-prefix
    (ebnf-elements-attributes elem-map attr-map)
    (slurp HTML5-EBNF-BASE)
    (slurp HTML5-EBNF-ATTR-VALS)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn prefix [ctx]
  (str
"(ns " (:namespace ctx) "
   (:require [clojure.test.check.generators :as gen]
             [com.gfredericks.test.chuck.generators :as chuck]
             [mend.util :as util]
             [" (:css-namespace ctx) " :refer [gen-css-assignments]]))

"))

(defn replace-css-gen
  "Replace the stub CSS value generator with real one."
  [grammar]
  (assoc grammar
         :attr-val-style
         {:tag :nt :keyword :css-assignments}))

(defn grammar->ns
  [ctx grammar]
  (let [g (replace-css-gen grammar)]
    (str (prefix ctx)
         (ebnf/grammar->generator-defs ctx g))))


(def cli-options
  (vec
    (concat
      ebnf/cli-options
      [[nil "--namespace NAMESPACE"
        "Name of namespace to generate"]
       [nil "--css-namespace CSS-NAMESPACE"
        "Name of the CSS namespace to require"]
       [nil "--ebnf-output EBNF-OUTPUT"
        "Write intermediate EBNF to file"]])))

(defn opt-errors [opts]
  (when (:errors opts)
    (map pr-err (:errors opts))
    (System/exit 2))
  opts)

(defn html5-ns [opts]
  (let [ctx (merge {:weights-res (atom {})}
                   (select-keys opts [:namespace :css-namespace :weights]))
        html5-ebnf-str (ebnf-all html5-elements html5-attributes)

        ;; The following each take 4-6 seconds
        _ (pr-err "Loading HTML5 grammar")
        html5-grammar (ebnf/load-grammar html5-ebnf-str)
        _ (pr-err "Converting HTML5 grammar to generators")
        ns-str (grammar->ns ctx html5-grammar)]

    (when-let [efile (:ebnf-output opts)]
      (pr-err "Saving EBNF to" efile)
      (spit efile html5-ebnf-str))

    (when-let [wfile (:weights-output opts)]
      (pr-err "Saving weights to" wfile)
      (ebnf/save-weights ctx (:weights-output opts)))

    ns-str))

(defn -main [& args]
  (let [opts (:options (opt-errors (parse-opts args cli-options)))]
    (when (not (:namespace opts))
      (pr-err "--namespace NAMESPACE required")
      (System/exit 2))
    (when (not (:css-namespace opts))
      (pr-err "--css-namespace CSS-NAMESPACE required")
      (System/exit 2))

    (println (html5-ns opts))))

(comment

  (spit "src/rend/html5_generators.clj"
        (html5-ns "rend.html5-generators" "rend.css-generators"))

  (require '[rend.html5-generators :as html5-gen] :reload)
  (pprint (gen/sample html5-gen/gen-html 10))

)

