(ns shadow.cljs.passes
  (:require [clojure.pprint :refer (pprint)]
            [cljs.util :as util]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.env :as env]))

(defn macro-js-requires [env {:keys [op] :as ast}]
  (if (and (= :ns op) (:use-macros ast))
    (let [requires (reduce (fn [requires [macro-name macro-ns]]
                             (let [{:keys [js-require] :as m} (-> (symbol (str macro-ns) (str macro-name))
                                                                  (find-var)
                                                                  (meta))]
                               (cond
                                 (symbol? js-require)
                                 (assoc requires js-require js-require)

                                 (coll? js-require)
                                 (reduce #(assoc %1 %2 %2) requires js-require)

                                 :else requires)
                               ))
                           (:requires ast {})
                           (:use-macros ast))]
      (assoc ast :requires requires))
    ast))


(defn load-macros [env {:keys [op name] :as ast}]
  (if (or (not= :ns op)
          (not (-> name meta :load-macros)))
    ast
    ;; only loads macros if the ns says so
    ;; (ns my-cljs-ns
    ;;    {:load-macros true})
    (do (require name)
        (let [macros (->> (ns-publics name)
                          (reduce-kv (fn [m var-name the-var]
                                       (if (.isMacro ^clojure.lang.Var the-var)
                                         (conj m var-name)
                                         m))
                                     #{}))]
          ;; this is sort of ugly, but the compiler env does not store the result of a pass, only the parse
          (swap! env/*compiler* assoc-in [::ana/namespaces name :macros] macros)
          (assoc ast :macros macros)
          ))))

(defn infer-macro-use [env {:keys [op uses name] :as ast}]
  (if (or (not= :ns op)
          (empty? uses))
    ast
    (reduce (fn [ast [used-name used-ns]]
              (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
                (if (contains? macros used-name)
                  (do (swap! env/*compiler* (fn [current]
                                              (-> current
                                                  (update-in [::ana/namespaces name :use-macros] merge {used-name used-ns})
                                                  (update-in [::ana/namespaces name :require-macros] assoc used-ns used-ns))))
                      (update-in ast [:use-macros] merge {used-name used-ns}))
                  ast
                  )))
            ast
            uses)))