(ns kioo.core
  (:require [kioo.util :refer [convert-attrs WrapComponent *component*
                               camel-case]]
            [hickory.core :as hic :refer [parse-fragment as-hiccup]]
            [sablono.core :as sab :include-macros true]))


(defn value-component[renderer]
  (let [react-component
        (.createClass js/React
           #js {:shouldComponentUpdate
                (fn [next-props _]
                  (this-as this
                           (not= (aget (.-props this) "value")
                                 (aget next-props "value"))))
                :render
                (fn []
                  (this-as this
                           (binding [*component* this]
                             (apply renderer
                                    (aget (.-props this) "value")
                                    (aget (.-props this) "statics")))))})]
    (fn [value & static-args]
      (react-component #js {:value value :statics static-args}))))


(defn flatten-nodes [nodes]
  (reduce #(if (seq? %2)
             (concat %2 %1)
             (conj %1 %2))
          '()
          (reverse nodes)))

(defn make-dom [node]
  (if (map? node)
      (apply (:sym node)
             (clj->js (:attrs node))
             (flatten-nodes (:content node)))
      node))

(defn to-list [vals]
  (if (seq? vals)
    vals
    (list vals)))

(defn handle-wrapper [dom-fn]
  (fn hw [node & body]
    (let [rnode (cond
                 (seq? node) (apply hw node)
                 (and (map? node) (not (empty? (:events node))))  
                 (let [revents (:events node)]
                   (WrapComponent (clj->js (assoc revents
                                             :wrappee (dom-fn node)))))
                 :else (dom-fn node))]
      (if (empty? body)
        rnode
        (cons rnode (to-list (apply hw body)))))))


(defn content [& body]
  (fn [node]
    (assoc node :content body)))

(defn append [& body]
  (fn [node]
    (assoc node :content (concat (:content node) body))))

(defn prepend [& body]
  (fn [node]
    (assoc node :content (concat body (:content node)))))

(defn after [& body]
  (fn [node]
    (cons (make-dom node) body)))

(defn before [& body]
  (fn [node]
    (flatten-nodes (concat body [(make-dom node)]))))

(defn substitute [& body]
  (fn [node] body))


(defn set-attr [& body]
  (let [els (partition 2 body)]
    (fn [node]
      (assoc node :attrs (reduce (fn [n [k v]]
                                  (assoc n k v))
                                (:attrs node) els)))))

(defn remove-attr [& body]
  (fn [node]
    (assoc node :attrs (reduce (fn [n k]
                                (dissoc n k))
                              (:attrs node) body))))

(defn do-> [& body]
  (fn [node]
    (reduce #(%2 %1) node body)))


(defn set-style [& body]
  (let [els (partition 2 body)
        mp (reduce (fn [m [k v]] (assoc m k v)) {} els)]
    (fn [node]
      (update-in node [:attrs :style] #(merge %1 mp)))))


(defn remove-style [& body]
  (apply set-style  (interleave body (repeat nil))))


(defn- get-class-regex [cls]
  (js/RegExp. (str "(\\s|^)" cls "(\\s|$)")))

(defn- has-class? [cur-cls cls]
  (.match cur-cls (get-class-regex cls)))


(defn set-class [& values]
  (fn [node]
    (let [new-class (reduce #(if (has-class? %1 %2)
                               %1
                               (str %1 " " %2))
                            ""
                            values)]
      (assoc-in node [:attrs :className] new-class))))


(defn add-class [& values]
  (fn [node]
    (let [new-class (reduce #(if (has-class? %1 %2)
                               %1
                               (str %1 " " %2))
                        (get-in node [:attrs :className])
                        values)]
      (assoc-in node [:attrs :className] new-class))))


(defn remove-class [& values]
  (fn [node]
    (let [new-class (reduce #(if (has-class? %1 %2)
                               (.replace %1 (get-class-regex %2) " ")
                               %1)
                        (get-in node [:attrs :className])
                        values)]
      (assoc-in node [:attrs :className] new-class))))

(defn wrap [tag attrs]
  (fn [node]
    {:tag tag
     :sym (aget js/React.DOM (name tag)) 
     :attrs (convert-attrs attrs)
     :content [(make-dom node)]}))

(defn unwrap [node]
  (:content node))


(defn html [content] (sab/html content))

(defn html-content [content]
  (fn [node]
    (let [children  (map #(-> % (as-hiccup) (sab/html))
                         (parse-fragment content))]
      (assoc node :content children))))

(def react-events #{"onRender" "onUpdate" "onMount"})

(defn listen [& events+fns]
  (let [pairs (map (fn [[k v]] [(camel-case k) v])
                   (partition 2 events+fns))
        [rev sev] (reduce (fn [[r s] [k v]]
                            (if (react-events k)
                              [(assoc r k v) s]
                              [r (assoc s k v)])) [] pairs)]
    (fn [node]
      (assoc node
        :attrs (merge (:attrs node) sev)
        :events (merge (:events node) rev)))))

(defn render [component node]
  (.renderComponent js/React component node))
