(ns send.core
  (:require [differ.core :as differ]
            [clojure.pprint :refer [pprint]]

            #?(:cljs [reagent.core :as r])
            [rend.util :as util]))

(defonce state
  (#?(:cljs r/atom :clj atom)
    {:connected false
     :tabs {}
     :test-state nil}))

(defn update-tabs [state-val]
  (let [cur-tabs (:tabs state-val)
        slugs (-> state-val :test-state :test-slugs)
        all-tabs (into {} (for [slug slugs]
                            [slug {:visible false
                                   :thumbs  false}]))]
    ;; Add new tabs without touching existing ones by merging
    ;; (shallow) the current tabs last
    (assoc state-val :tabs
           (merge all-tabs cur-tabs))))

(defn msg-handler [state msg]
  (let [{:keys [msgType data]} msg]
    ;;(prn :msgType msgType :data data)
    (condp = msgType
      :full
      (swap! state assoc :test-state data)

      :patch
      (do (prn :patch-data data)
      (swap! state update-in [:test-state] differ/patch data))

      :merge
      (swap! state (fn [{:keys [test-state] :as cur-state}]
                     (assoc cur-state :test-state
                            (util/merge-test-state cur-state data))))

      (println "ignoring msgType" msgType))
    ;; Sync tab state
    (swap! state update-tabs)))

(defn ^:export print-log-state []
    (pprint (assoc-in @state [:test-state :log] :ELIDED)))

(defn ^:export print-top-state []
    (pprint (assoc-in @state [:test-state] :ELIDED)))


