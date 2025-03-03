(ns clojure-lsp.feature.document-symbol
  (:require
   [clojure-lsp.parser :as parser]
   [clojure-lsp.queries :as q]
   [clojure-lsp.shared :as shared]
   [rewrite-clj.zip :as z]))

(set! *warn-on-reflection* true)

(def ^:private defines-interface?
  '#{clojure.core/defprotocol cljs.core/defprotocol
     clojure.core/definterface cljs.core/definterface
     clojure.core/defmulti cljs.core/defmulti})

(def ^:private defines-class?
  '#{clojure.core/defrecord cljs.core/defrecord
     clojure.core/deftype cljs.core/deftype})

(defn element->symbol-kind [{:keys [bucket] :as el}]
  (case bucket
    (:namespace-usages :namespace-definitions) :namespace
    :var-definitions (cond
                       (or (:fixed-arities el) (:varargs-min-arity el) (:macro el))
                       #_=> :function
                       (some->> el :defined-by defines-interface?)
                       #_=> :interface
                       (some->> el :defined-by defines-class?)
                       #_=> :class
                       :else
                       #_=> :variable)
    :var-usages (if (:defmethod el)
                  :function
                  :variable)
    :keyword-usages :field
    :null))

(defn element->name [{elem-name :name symbol :symbol :keys [dispatch-val-str]}]
  (cond-> (or (some-> symbol str) (name elem-name))
    dispatch-val-str (str " " dispatch-val-str)))

(defn ^:private element->document-symbol [e]
  (shared/assoc-some
    {:name (element->name e)
     :kind (element->symbol-kind e)
     :range (shared/->scope-range e)
     :selection-range (shared/->range e)
     :tags (cond-> []
             (:deprecated e) (conj 1))}
    :detail (when (:private e)
              "private")))

(defn ^:private symbol-order [{:keys [selection-range]}]
  [(:line (:start selection-range)) (:character (:start selection-range))])

(defn ^:private remove-first [item-to-remove coll]
  (if (empty? coll)
    ()  ; literal name for empty list
    (let [item (first coll)]
      (if (= item item-to-remove)
        (rest coll)
        (cons (first coll) (lazy-seq (remove-first item-to-remove (rest coll))))))))

(defn ^:private edn->element-tree [m keyword-elements symbol-elements]
  (sort-by symbol-order
           (reduce
             (fn [acc [k v]]
               (let [element (if (keyword? k)
                               (first (filter #(= (:name %) (name k)) keyword-elements))
                               (first (filter #(= (:symbol %) k) symbol-elements)))
                     document-symbol (element->document-symbol element)
                     kind (cond
                            (string? v) :string
                            (keyword? v) :field
                            (boolean? v) :boolean
                            (vector? v) :array
                            (set? v) :array
                            :else :struct)
                     document-symbol (assoc document-symbol :kind kind)]
                 (if (map? v)
                   (conj acc (assoc document-symbol
                                    :children
                                    (edn->element-tree
                                      v
                                      (remove-first element keyword-elements)
                                      (remove-first element symbol-elements))))
                   (conj acc document-symbol))))
             []
             m)))

(defn document-symbols [db uri]
  (let [namespace-definition (q/find-namespace-definition-by-uri db uri)]
    (if (identical? :edn (shared/uri->file-type uri))
      (-> (parser/zloc-of-file db uri)
          z/sexpr
          (edn->element-tree (get-in db [:analysis uri :keyword-usages])
                             (get-in db [:analysis uri :symbols])))
      [{:name (or (some-> namespace-definition :name name)
                  ;; TODO Consider using URI for display purposes, especially if
                  ;; we support remote LSP connections
                  (shared/uri->filename uri))
        :kind (element->symbol-kind namespace-definition)
        :range shared/full-file-range
        :selection-range (if namespace-definition
                           (shared/->scope-range namespace-definition)
                           shared/full-file-range)
        :children (->> (concat (q/find-var-definitions db uri true)
                               (q/find-defmethods db uri))
                       (map element->document-symbol)
                       (sort-by symbol-order)
                       vec)}])))
