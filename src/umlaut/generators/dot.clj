(ns umlaut.generators.dot
  (:require [clojure.java.io :as io]
            [umlaut.models :as model]
            [umlaut.core :as core]
            [umlaut.utils :refer :all]
            [clojure.spec :as s]
            [clojure.string :as string]
            [clojure.spec.test :as stest]))
(use '[clojure.java.shell :only [sh]])
(use '[clojure.pprint :only [pprint]])

(def header (slurp (io/resource "templates/header.dot")))

(def footer (slurp (io/resource "templates/footer.dot")))

;; Store all edges added in a subgraph, so we don't have redundant edges
(def ^:private edges (atom []))

(defn- arity-label
  "Builds the arity representation string in the diagram"
  [[from to]]
  (if (= from to) "" (str "[" from ".." to "]")))

(defn- required-label [type-obj]
  "Checks required attribute and returns ? or not"
  (if (type-obj :required)
    ""
    "?"))

(defn- type-label [type]
  "Receives a ::attribute object and builds the string for its type"
  (str (type :type-id) (arity-label (type :arity)) (required-label type)))

(defn- attribute-label [method]
  "Receives a ::method object and build its complete label"
  (str (:id method) ": " (type-label (method :return)) "\\l"))

(defn- method-args-label [method]
  "Iterates over all the method arguments and builds the string"
  (->> (method :params)
    (map #(str (%1 :id) ": " (type-label %1)))
    (string/join ", ")))

(defn- method-label [method]
  "Receives a ::method object and build its complete label"
  (str (method :id) "(" (method-args-label method) "): " (type-label (method :return)) "\\l"))

(defn- fields-list [fields]
  (reduce (fn [acc method]
            (str acc (if (method :params?)
                        (method-label method)
                        (attribute-label method))))
    "" fields))

(defn- values-list
  [{:keys [values]}]
  (reduce #(str %1 %2 "\\l") "" values))

(defn- node-label
  "Builds the string regarding object type"
  [kind type-obj]
  (case kind
    :type (str (:id type-obj) "|" (fields-list (type-obj :fields)))
    :interface (str "\\<\\<interface\\>\\>" (:id type-obj) "|" (fields-list (type-obj :fields)))
    :enum (str "\\<\\<enum\\>\\>" (:id type-obj) "|" (values-list type-obj))
    ""))

(defn- node-str
  "Receives an AST node and generates the dotstring of the node"
  [[kind type-obj]]
  (str "  " (:id type-obj)
    " [label = \"{"
    (node-label kind type-obj)
    "}\"]"))

(defn- gen-subgraph-content
  [umlaut]
  (reduce (fn [acc [id node]]
            (str acc (node-str node) "\n"))
    "" (seq (umlaut :nodes))))

(defn- subgraph-id [umlaut]
  (->> (seq (umlaut :nodes))
       (first)
       (first)))

(defn gen-subgraph
  [umlaut]
  (let [ns-id (subgraph-id umlaut)]
   (str "subgraph "
        ns-id
        " {\n  label = \""
        ns-id
        "\"\n"
        (gen-subgraph-content umlaut)
        "}\n")))

(defn- get-group-set
  "Receives a umlaut structure and returns a set with all the boxes that will be drawn"
  [umlaut]
  (if (umlaut :current)
    (set (flatten ((second (umlaut :current)) :groups)))
    (set [])))

(defn- draw-edge? [node umlaut]
  (or
    (contains? (get-group-set umlaut) (node :type-id))
    (in? (node :type-id) (keys (umlaut :nodes)))))

(defn- filter-attr-in-map
  "Filter attributes of a declaration block that are inside any of the groups"
  [attributes umlaut]
  (filter #(draw-edge? % umlaut) attributes))

(defn- filter-methods-in-map
  "Filter methods of a declaration block that are inside any of the groups"
  [methods umlaut]
  (filter #(draw-edge? (% :return) umlaut) methods))

(defn- get-all-param-types [methods]
  (flatten (reduce (fn [acc el] (conj acc (el :params))) [] methods)))

(defn- filter-args-in-map
  "Filter method arguments of a declaration block that are inside any of the groups"
  [methods umlaut]
  (let [args (get-all-param-types methods)]
    (filter #(draw-edge? % umlaut) args)))

(defn- method-types-from-node [node umlaut]
  "Returns a list of Strings of all non primitive types"
  (map #((% :return) :type-id) (filter-methods-in-map (node :fields) umlaut)))

(defn- method-args-from-node [node umlaut]
  "Returns a list of Strings of all non primitive types"
  (map #(% :type-id) (filter-args-in-map (node :fields) umlaut)))

(defn- edge-label [src dst]
  "Returns a dot string that represents an edge"
  (let [label (str src " -> " dst "\n")]
    (if (not (in? label (deref edges)))
      (do
        (swap! edges conj label)
        label)
      "")))

(defn- edge-inheritance-label [src dst]
  "Returns a dot string that represents an inheritance edge"
  (str "edge [arrowhead = \"empty\"]\n" (edge-label src dst) "\nedge [arrowhead = \"open\"]\n"))

(defn- edge-params-label [src dst]
  "Returns a dot string that represents an inheritance edge"
  (str (edge-label src dst) " [style=dotted]\n"))

(defn- build-edges-fields [node umlaut]
  "Builds a string with all the regular edges between a type and its methods"
  (str
    (string/join "\n" (map (fn [type] (edge-label (node :id) type)) (method-types-from-node node umlaut)))
    (string/join "\n" (map (fn [type] (edge-params-label (node :id) type)) (method-args-from-node node umlaut)))))

(defn- build-edges-inheritance [node parents umlaut]
  "Builds a string with all inheritance edges (multiple inheritance case)"
  (string/join "\n" (map (fn [parent]
                          (edge-inheritance-label (node :id) (get parent :type-id)))
                      (filter-attr-in-map parents umlaut))))

(defn- contain-parents? [node]
  "Whether the type inherits from other types or not"
  (and (contains? node :parents) (> (count (get node :parents)) 0)))

(defn gen-edges
  [umlaut]
  "Generate a string with all the edges that should be drawn"
  (reduce (fn [acc [_ node]]
            (let [block (second node)]
              (str acc (build-edges-fields block umlaut)
                (when (contain-parents? block)
                  (build-edges-inheritance block (block :parents) umlaut)))))
   "" (seq (umlaut :nodes))))

(defn gen-subgraphs-string [umlaut]
  "Generate the dot language subgraph and its edges"
  (str (gen-subgraph umlaut) (gen-edges umlaut)))

(defn format-filename [filename]
  "Ensures that the output file is saved in the output folder"
  (str "output/" filename ".png"))

(defn save-diagram [filename dotstring]
  (println (str "Saving " filename))
  ; (println dotstring)
  (let [error (sh "dot" "-Tpng" "-o" filename :in dotstring)]
    (when (= (error :exit) 1)
      (throw (Exception. (with-out-str (pprint error)))))))

(defn required-nodes [required coll]
  "Returns a map of all the nodes inside of coll that are in the required vector"
  (zipmap (sort required) (map second (sort-by first (filter #(in? (first %) required) coll)))))

(defn- remove-extra-nodes [diagram required umlaut]
  "Rebuild umlaut structure with the nodes key containing only required nodes for the diagram"
  (assoc (umlaut-base (required-nodes required (seq (umlaut :nodes))) (umlaut :diagrams))
    :current ((umlaut :diagrams) diagram)))

(defn- gen-dotstring [subgraphs]
  "Concatenates a fixed header, the subgraph string generated, and a fixed footer"
  (str header subgraphs footer))

(defn- filter-not-primitives-methods
  "Filter attributes of a declaration block that are not primitive"
  [methods]
  (let [all-types (concat
                    (map #(% :type-id) (get-all-param-types methods))
                    (map #(% :type-id) (map #(% :return) methods)))]
    (distinct (filter not-primitive? all-types))))

(defn- non-primitive-related-nodes [node]
  "Returns a list of Strings of all non primitive types"
  (if node
    (filter-not-primitives-methods (node :fields))
    ()))

(defn- adjacent-nodes [key graph]
  (let [block (second (graph key))
        adjs (non-primitive-related-nodes block)]
    (if (contain-parents? block)
      (flatten (merge adjs (map #(% :type-id) (block :parents))))
      adjs)))

(defn- dfs
  "Traverses a map given a starting point"
  [current graph visited]
  (if (not (in? current visited))
    (let [new-visited (distinct (conj visited current))
          attrs (adjacent-nodes current graph)]
      (if (> (count attrs) 0)
        (for [att attrs]
          (dfs att graph new-visited))
        new-visited))
    visited))

(defn- get-nodes-recursively [start umlaut]
  "Flatten all the reachable nodes from a starting node"
  (flatten (dfs start (umlaut :nodes) ())))

(defn- create-group [group umlaut]
  (if (= (last group) "!")
    (reduce (fn [acc start]
              (distinct (concat acc (get-nodes-recursively start umlaut))))
      [] (drop-last group))
    group))

(defn gen-all [umlaut]
  (let [dotstring (->> umlaut
                    (gen-subgraphs-string)
                    (gen-dotstring))]
    (save-diagram (format-filename "all") dotstring)
    dotstring))

(defn gen-by-group [umlaut]
  (reduce
    (fn [acc [diagram-name node]]
      (def ^:private edges (atom []))
      (let
        [curr
          (->> ((second node) :groups)
            (reduce
              (fn [acc group]
                (str acc
                  (gen-subgraphs-string
                    (remove-extra-nodes diagram-name (create-group group umlaut) umlaut))))
              "")
            (gen-dotstring))]
        (save-diagram (format-filename diagram-name) curr)
        (assoc acc (format-filename diagram-name) curr)))
    {} (seq (umlaut :diagrams))))

(defn gen [umlaut]
  (gen-by-group umlaut)
  (gen-all umlaut))

(defn gen-diagrams [path]
  (gen (core/main path)))

; (gen-by-group (core/main ["test/fixtures/person/person.umlaut" "test/fixtures/person/profession.umlaut"]))
; (gen-by-group (core/main ["test/philz/main.umlaut"]))
