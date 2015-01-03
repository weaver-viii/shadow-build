(ns shadow.cljs.passes
  (:require [clojure.pprint :refer (pprint)]
            [cljs.util :as util]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.env :as env]))

(defn load-macros [_ {:keys [op name] :as ast}]
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

          (swap! env/*compiler* assoc-in [::ana/namespaces name :macros] macros)
          (assoc ast :macros macros)
          ))))

(defn infer-macro-require [_ {:keys [op requires name] :as ast}]
  (if (or (not= :ns op)
          (empty? requires))
    ast
    (reduce (fn [ast [used-name used-ns]]
              (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
                (if (nil? macros)
                  ast
                  (let [update-fn (fn [current]
                                    (update-in current [:require-macros] assoc used-name used-ns))]

                    (swap! env/*compiler* update-in [::ana/namespaces name] update-fn)
                    (update-fn ast))
                  )))
            ast
            requires)))


(defn infer-macro-use [_ {:keys [op uses name] :as ast}]
  (if (or (not= :ns op)
          (empty? uses))
    ast
    (reduce (fn [ast [used-name used-ns]]
              (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
                (if (contains? macros used-name)
                  (let [update-fn (fn [current]
                                    (update-in current [:use-macros] merge {used-name used-ns}))]

                    (swap! env/*compiler* update-in [::ana/namespaces name] update-fn)
                    (update-fn ast))
                  ast
                  )))
            ast
            uses)))



