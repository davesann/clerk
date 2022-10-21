(ns nextjournal.clerk.render
  (:require ["d3-require" :as d3-require]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [goog.object]
            [goog.string :as gstring]
            [nextjournal.clerk.viewer :as viewer]
            [nextjournal.markdown.transform :as md.transform]
            [nextjournal.ui.components.icon :as icon]
            [nextjournal.ui.components.motion :as motion]
            [nextjournal.ui.components.navbar :as navbar]
            [nextjournal.view.context :as view-context]
            [nextjournal.viewer.code :as code]
            [nextjournal.viewer.katex :as katex]
            [nextjournal.viewer.mathjax :as mathjax]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [reagent.ratom :as ratom]))


(when (exists? js/window)
  ;; conditionalized currently because this throws in node
  ;; TypeError: Cannot assign to read only property 'reagentRender' of object '#<Object>'
  (r/set-default-compiler! (r/create-compiler {:function-components true})))

(declare inspect inspect-presented reagent-viewer html html-viewer)

(def nbsp (gstring/unescapeEntities "&nbsp;"))

(defn toc-items [items]
  (reduce
   (fn [acc {:as item :keys [content children]}]
     (if content
       (let [title (md.transform/->text item)]
         (->> {:title title
               :path (str "#" (viewer/->slug title))
               :items (toc-items children)}
              (conj acc)
              vec))
       (toc-items (:children item))))
   []
   items))

(defn dark-mode-toggle [!state]
  (let [{:keys [dark-mode?]} @!state
        spring {:type :spring :stiffness 200 :damping 10}]
    [:div.relative.dark-mode-toggle
     [:button.text-slate-400.hover:text-slate-600.dark:hover:text-white.cursor-pointer
      {:on-click #(swap! !state assoc :dark-mode? (not dark-mode?))}
      (if dark-mode?
        [:> motion/svg
         {:xmlns "http://www.w3.org/2000/svg"
          :class "w-5 h-5 md:w-4 md:h-4"
          :viewBox "0 0 50 50"
          :key "moon"}
         [:> motion/path
          {:d "M 43.81 29.354 C 43.688 28.958 43.413 28.626 43.046 28.432 C 42.679 28.238 42.251 28.198 41.854 28.321 C 36.161 29.886 30.067 28.272 25.894 24.096 C 21.722 19.92 20.113 13.824 21.683 8.133 C 21.848 7.582 21.697 6.985 21.29 6.578 C 20.884 6.172 20.287 6.022 19.736 6.187 C 10.659 8.728 4.691 17.389 5.55 26.776 C 6.408 36.163 13.847 43.598 23.235 44.451 C 32.622 45.304 41.28 39.332 43.816 30.253 C 43.902 29.96 43.9 29.647 43.81 29.354 Z"
           :fill "currentColor"
           :initial "initial"
           :animate "animate"
           :variants {:initial {:scale 0.6 :rotate 90}
                      :animate {:scale 1 :rotate 0 :transition spring}}}]]
        [:> motion/svg
         {:key "sun"
          :class "w-5 h-5 md:w-4 md:h-4"
          :viewBox "0 0 24 24"
          :fill "none"
          :xmlns "http://www.w3.org/2000/svg"}
         [:> motion/circle
          {:cx "11.9998"
           :cy "11.9998"
           :r "5.75375"
           :fill "currentColor"
           :initial "initial"
           :animate "animate"
           :variants {:initial {:scale 1.5}
                      :animate {:scale 1 :transition spring}}}]
         [:> motion/g
          {:initial "initial"
           :animate "animate"
           :variants {:initial {:rotate 45}
                      :animate {:rotate 0 :transition spring}}}
          [:circle {:cx "3.08982" :cy "6.85502" :r "1.71143" :transform "rotate(-60 3.08982 6.85502)" :fill "currentColor"}]
          [:circle {:cx "3.0903" :cy "17.1436" :r "1.71143" :transform "rotate(-120 3.0903 17.1436)" :fill "currentColor"}]
          [:circle {:cx "12" :cy "22.2881" :r "1.71143" :fill "currentColor"}]
          [:circle {:cx "20.9101" :cy "17.1436" :r "1.71143" :transform "rotate(-60 20.9101 17.1436)" :fill "currentColor"}]
          [:circle {:cx "20.9101" :cy "6.8555" :r "1.71143" :transform "rotate(-120 20.9101 6.8555)" :fill "currentColor"}]
          [:circle {:cx "12" :cy "1.71143" :r "1.71143" :fill "currentColor"}]]])]]))


(defn localstorage-set! [key val]
  (when (exists? js/window)
    (.setItem (.-localStorage js/window) key val)))

(defn localstorage-get [key]
  (when (exists? js/window)
    (cljs.reader/read-string (.getItem (.-localStorage js/window) key))))


(def local-storage-dark-mode-key "clerk-darkmode")

(defn set-dark-mode! [dark-mode?]
  (let [class-list (.-classList (js/document.querySelector "html"))]
    (if dark-mode?
      (.add class-list "dark")
      (.remove class-list "dark")))
  (localstorage-set! local-storage-dark-mode-key dark-mode?))

(defn setup-dark-mode! [!state]
  (let [{:keys [dark-mode?]} @!state]
    (add-watch !state ::dark-mode
               (fn [_ _ old {:keys [dark-mode?]}]
                 (when (not= (:dark-mode? old) dark-mode?)
                   (set-dark-mode! dark-mode?))))
    (when dark-mode?
      (set-dark-mode! dark-mode?))))

(defonce !eval-counter (r/atom 0))

(defn render-notebook [{:as _doc xs :blocks :keys [bundle? toc toc-visibility]}]
  (r/with-let [local-storage-key "clerk-navbar"
               !state (r/atom {:toc (toc-items (:children toc))
                               :md-toc toc
                               :dark-mode? (localstorage-get local-storage-dark-mode-key)
                               :theme {:slide-over "bg-slate-100 dark:bg-gray-800 font-sans border-r dark:border-slate-900"}
                               :width 220
                               :mobile-width 300
                               :local-storage-key local-storage-key
                               :set-hash? (not bundle?)
                               :open? (if-some [stored-open? (localstorage-get local-storage-key)]
                                        stored-open?
                                        (not= :collapsed toc-visibility))})
               root-ref-fn #(when % (setup-dark-mode! !state))
               ref-fn #(when % (swap! !state assoc :scroll-el %))]
    (let [{:keys [md-toc]} @!state]
      (when-not (= md-toc toc)
        (swap! !state assoc :toc (toc-items (:children toc)) :md-toc toc :open? (not= :collapsed toc-visibility)))
      (html
       [:div.flex
        {:ref root-ref-fn}
        [:div.fixed.top-2.left-2.md:left-auto.md:right-2.z-10
         [dark-mode-toggle !state]]
        (when (and toc toc-visibility)
          [:<>
           [navbar/toggle-button !state
            [:<>
             [icon/menu {:size 20}]
             [:span.uppercase.tracking-wider.ml-1.font-bold
              {:class "text-[12px]"} "ToC"]]
            {:class "z-10 fixed right-2 top-2 md:right-auto md:left-3 md:top-3 text-slate-400 font-sans text-xs hover:underline cursor-pointer flex items-center bg-white dark:bg-gray-900 py-1 px-3 md:p-0 rounded-full md:rounded-none border md:border-0 border-slate-200 dark:border-gray-500 shadow md:shadow-none dark:text-slate-400 dark:hover:text-white"}]
           [navbar/panel !state [navbar/navbar !state]]])
        [:div.flex-auto.h-screen.overflow-y-auto.scroll-container
         {:ref ref-fn}
         [:div.flex.flex-col.items-center.viewer-notebook.flex-auto
          (doall
           (map-indexed (fn [idx x]
                          (let [{viewer-name :name} (viewer/->viewer x)
                                inner-viewer-name (some-> x viewer/->value viewer/->viewer :name)]
                            ^{:key (str idx "-" @!eval-counter)}
                            [:div {:class ["viewer"
                                           (when viewer-name (str "viewer-" (name viewer-name)))
                                           (when inner-viewer-name (str "viewer-" (name inner-viewer-name)))
                                           (case (or (viewer/width x) (case viewer-name (:code :code-folded) :wide :prose))
                                             :wide "w-full max-w-wide"
                                             :full "w-full"
                                             "w-full max-w-prose px-8")]}
                             [inspect-presented x]]))
                        xs))]]]))))

(defn opts->query [opts]
  (->> opts
       (map #(update % 0 name))
       (map (partial str/join "="))
       (str/join "&")))


#_(opts->query {:s 12 :num 42})



(defn render-unreadable-edn [edn]
  (html [:span.inspected-value.whitespace-nowrap.cmt-default edn]))

(defn error-badge [& content]
  [:div.bg-red-50.rounded-sm.text-xs.text-red-400.px-2.py-1.items-center.sans-serif.inline-flex
   [:svg.h-4.w-4.text-red-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
    [:path {:fill-rule "evenodd" :d "M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" :clip-rule "evenodd"}]]
   (into [:div.ml-2.font-bold] content)])

(defn error-view [error]
  [:div.bg-red-100.dark:bg-gray-800.px-6.py-4.rounded-md.text-xs.dark:border-2.dark:border-red-300.not-prose
   [:p.font-mono.text-red-600.dark:text-red-300.font-bold (.-message error)]
   [:pre.text-red-600.dark:text-red-300.w-full.overflow-auto.mt-2
    {:class "text-[11px] max-h-[155px]"}
    (try
      (->> (.-stack error)
           str/split-lines
           (drop 1)
           (mapv str/trim)
           (str/join "\n"))
      (catch js/Error _ nil))]
   (when-some [data (.-data error)]
     [:div.mt-2 [inspect data]])])




(defn error-boundary [!error & _]
  (r/create-class
   {:constructor (fn [_ _])
    :component-did-catch (fn [_ e _info] (reset! !error e))
    :get-derived-state-from-error (fn [e] (reset! !error e) #js {})
    :reagent-render (fn [_error & children]
                      (if-let [error @!error]
                        (error-view error)
                        [view-context/provide {:!error !error}
                         (into [:<>] children)]))}))

(def default-loading-view "Loading...")

(defn use-handle-error []
  (partial reset! (react/useContext (view-context/get-context :!error))))

;; TODO: drop this
(defn read-string [s]
  (js/nextjournal.clerk.sci_env.read-string s))


(defn fetch! [{:keys [blob-id]} opts]
  #_(js/console.log :fetch! blob-id opts)
  (-> (js/fetch (str "_blob/" blob-id (when (seq opts)
                                        (str "?" (opts->query opts)))))
      (.then #(.text %))
      (.then #(try (read-string %)
                   (catch js/Error e
                     (js/console.error #js {:message "sci read error" :blob-id blob-id :code-string % :error e })
                     (render-unreadable-edn %))))))

(defn read-result [{:nextjournal/keys [edn string]} !error]
  (if edn
    (try
      (read-string edn)
      (catch js/Error e
        (reset! !error e)))
    (render-unreadable-edn string)))

(defn render-result [{:as result :nextjournal/keys [fetch-opts hash]} _opts]
  (html (r/with-let [!hash (atom hash)
                     !error (r/atom nil)
                     !desc (r/atom (read-result result !error))
                     !fetch-opts (atom fetch-opts)
                     fetch-fn (when @!fetch-opts
                                (fn [opts]
                                  (.then (fetch! @!fetch-opts opts)
                                         (fn [more]
                                           (swap! !desc viewer/merge-presentations more opts)))))
                     !expanded-at (r/atom (get @!desc :nextjournal/expanded-at {}))
                     on-key-down (fn [event]
                                   (if (.-altKey event)
                                     (swap! !expanded-at assoc :prompt-multi-expand? true)
                                     (swap! !expanded-at dissoc :prompt-multi-expand?)))
                     on-key-up #(swap! !expanded-at dissoc :prompt-multi-expand?)
                     ref-fn #(if %
                               (when (exists? js/document)
                                 (js/document.addEventListener "keydown" on-key-down)
                                 (js/document.addEventListener "keyup" on-key-up))
                               (when (exists? js/document)
                                 (js/document.removeEventListener "keydown" on-key-down)
                                 (js/document.removeEventListener "up" on-key-up)))]
          (when-not (= hash @!hash)
            ;; TODO: simplify
            (reset! !hash hash)
            (reset! !fetch-opts fetch-opts)
            (reset! !desc (read-result result !error))
            (reset! !error nil))
          [view-context/provide {:fetch-fn fetch-fn}
           [error-boundary !error
            [:div.relative
             [:div.overflow-y-hidden
              {:ref ref-fn}
              [inspect-presented {:!expanded-at !expanded-at} @!desc]]]]])))

(defn toggle-expanded [!expanded-at path event]
  (.preventDefault event)
  (.stopPropagation event)
  (let [{:keys [hover-path prompt-multi-expand?]} @!expanded-at
        hover-path-count (count hover-path)
        hover-path-expanded? (get @!expanded-at path)]
    (if (and hover-path prompt-multi-expand? (= (count path) hover-path-count))
      (swap! !expanded-at (fn [expanded-at]
                            (reduce
                             (fn [acc [path expanded?]]
                               (if (and (coll? path) (vector? path) (= (count path) hover-path-count))
                                 (assoc acc path (not hover-path-expanded?))
                                 (assoc acc path expanded?)))
                             {}
                             expanded-at)))
      (swap! !expanded-at update path not))))


(defn expandable? [xs]
  (< 1 (count xs)))


(defn inspect-children [opts]
  ;; TODO: move update function onto viewer
  (map-indexed (fn [idx x]
                 (inspect-presented (update (assoc opts :idx idx) :path (fnil conj []) idx) x))))

(def expand-style
  ["cursor-pointer"
   "bg-indigo-50"
   "hover:bg-indigo-100"
   "border-b"
   "border-gray-400"
   "hover:border-gray-500"
   "dark:bg-gray-900"
   "dark:hover:bg-slate-700"
   "dark:border-slate-600"
   "dark:hover:border-slate-500"])

(defn triangle [expanded?]
  [:svg {:viewBox "0 0 100 100"
         :class (str "w-[7px] h-[7px] fill-current inline-block transition-all mr-[1px] -mt-[2px] "
                     (if expanded? "rotate-180" "rotate-90"))}
   [:polygon {:points "5.9,88.2 50,11.8 94.1,88.2 "}]])

(def triangle-spacer [:span {:class "inline-block w-[8px]"}])

(defn expand-button [!expanded-at opening-paren path]
  (let [expanded? (get @!expanded-at path)
        {:keys [hover-path prompt-multi-expand?]} @!expanded-at
        multi-expand? (and hover-path prompt-multi-expand? (= (count path) (count hover-path)))]
    [:span.group.hover:bg-indigo-100.rounded-sm.hover:shadow.cursor-pointer
     {:class (when multi-expand? "bg-indigo-100 shadow ")
      :on-click (partial toggle-expanded !expanded-at path)
      :on-mouse-enter #(swap! !expanded-at assoc :hover-path path)
      :on-mouse-leave #(swap! !expanded-at dissoc :hover-path)}
     [:span.text-slate-400.group-hover:text-indigo-700
      {:class (when multi-expand? "text-indigo-700 ")}
      [triangle expanded?]]
     [:span.group-hover:text-indigo-700 opening-paren]]))

(defn coll-view [xs {:as opts :keys [path viewer !expanded-at] :or {path []}}]
  (let [expanded? (get @!expanded-at path)
        {:keys [opening-paren closing-paren]} viewer]
    [:span.inspected-value.whitespace-nowrap
     {:class (when expanded? "inline-flex")}
     [:span
      (if (< 1 (count xs))
        [expand-button !expanded-at opening-paren path]
        [:span opening-paren])
      (into [:<>]
            (comp (inspect-children opts)
                  (interpose (if expanded? [:<> [:br] triangle-spacer nbsp (when (= 2 (count opening-paren)) nbsp)] " ")))
            xs)
      [:span
       (cond->> closing-paren (list? closing-paren) (into [:<>]))]]]))

(defn render-coll [xs opts]
  (html (coll-view xs opts)))

(defn render-elision [{:as fetch-opts :keys [total offset unbounded?]} _]
  (html [view-context/consume :fetch-fn
         (fn [fetch-fn]
           [:span.sans-serif.relative.whitespace-nowrap
            {:style {:border-radius 2 :padding (when (fn? fetch-fn) "1px 3px") :font-size 11 :top -1}
             :class (if (fn? fetch-fn)
                      "cursor-pointer bg-indigo-200 hover:bg-indigo-300 dark:bg-gray-700 dark:hover:bg-slate-600 text-gray-900 dark:text-white"
                      "text-gray-400 dark:text-slate-300")
             :on-click #(when (fn? fetch-fn)
                          (fetch-fn fetch-opts))} (- total offset) (when unbounded? "+") (if (fn? fetch-fn) " more…" " more elided")])]))

(defn map-view [xs {:as opts :keys [path viewer !expanded-at] :or {path []}}]
  (let [expanded? (get @!expanded-at path)
        {:keys [closing-paren]} viewer]
    [:span.inspected-value.whitespace-nowrap
     {:class (when expanded? "inline-flex")}
     [:span
      (if (expandable? xs)
        [expand-button !expanded-at "{" path]
        [:span "{"])
      (into [:<>]
            (comp (inspect-children opts)
                  (interpose (if expanded? [:<> [:br] triangle-spacer nbsp #_(repeat (inc (count path)) nbsp)] " ")))
            xs)
      (cond->> closing-paren (list? closing-paren) (into [:<>]))]]))

(defn render-map [xs opts] (html (map-view xs opts)))

(defn render-string [s {:as opts :keys [path !expanded-at] :or {path []}}]
  (html
   (let [expanded? (get @!expanded-at path)]
     (into [:span]
           (map #(if (string? %)
                   (if expanded?
                     (into [:<>] (interpose [:<> [:br]] (str/split-lines %)))
                     (into [:<>] (interpose [:span.text-slate-400 "↩︎"] (str/split-lines %))))
                   (inspect-presented opts %)))
           (if (string? s) [s] s)))))

(defn render-quoted-string [s {:as opts :keys [path viewer !expanded-at] :or {path []}}]
  (let [{:keys [closing-paren]} viewer]
    (html [:span.cmt-string.inspected-value.whitespace-nowrap.inline-flex
           [:span
            (if (some #(and (string? %) (str/includes? % "\n")) (if (string? s) [s] s))
              [expand-button !expanded-at "\"" path]
              [:span "\""])]
           [:div
            (viewer/->value (render-string s opts))
            "\""
            closing-paren]])))

(defn render-number [num]
  (html [:span.cmt-number.inspected-value
         (if (js/Number.isNaN num) "NaN" (str num))]))

(defn sort! [!sort i k]
  (let [{:keys [sort-key sort-order]} @!sort]
    (reset! !sort {:sort-index i
                   :sort-key k
                   :sort-order (if (= sort-key k) (if (= sort-order :asc) :desc :asc) :asc)})))

(defn sort-data [{:keys [sort-index sort-order]} {:as data :keys [head rows]}]
  (cond-> data
    head (assoc :rows (->> rows
                           (sort-by #(cond-> (get % sort-index)
                                       (string? val) str/lower-case)
                                    (if (= sort-order :asc) #(compare %1 %2) #(compare %2 %1)))
                           vec))))

(def x-icon
  [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
   [:path {:fill-rule "evenodd" :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(def check-icon
  [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
   [:path {:fill-rule "evenodd" :d "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" :clip-rule "evenodd"}]])

(defn render-table-error [[data]]
  ;; currently boxing the value in a vector to retain the type info
  ;; TODO: find a better way to do this
  (html
   [:div.bg-red-100.dark:bg-gray-800.px-6.py-4.rounded-md.text-xs.dark:border-2.dark:border-red-400.not-prose
    [:h4.mt-0.uppercase.text-xs.dark:text-red-400.tracking-wide "Table Error"]
    [:p.mt-4.font-medium "Clerk’s table viewer does not recognize the format of your data:"]
    [:div.mt-2.flex
     [:div.text-red-500.mr-2 x-icon]
     [inspect-presented data]]
    [:p.mt-4.font-medium "Currently, the following formats are supported:"]
    [:div.mt-2.flex.items-center
     [:div.text-green-500.mr-2 check-icon]
     [inspect {:column-1 [1 2]
               :column-2 [3 4]}]]
    [:div.mt-2.flex.items-center
     [:div.text-green-500.mr-2 check-icon]
     [inspect [{:column-1 1 :column-2 3} {:column-1 2 :column-2 4}]]]
    [:div.mt-2.flex.items-center
     [:div.text-green-500.mr-2 check-icon]
     [inspect [[1 3] [2 4]]]]
    [:div.mt-2.flex.items-center
     [:div.text-green-500.mr-2 check-icon]
     [inspect {:head [:column-1 :column-2]
               :rows [[1 3] [2 4]]}]]]))


(defn throwable-view [{:keys [via trace]}]
  [:div.bg-white.max-w-6xl.mx-auto.text-xs.monospace.not-prose
   (into
    [:div]
    (map
     (fn [{:as _ex :keys [type message data _trace]}]
       [:div.p-4.bg-red-100.border-b.border-b-gray-300
        [:div.font-bold "Unhandled " type]
        [:div.font-bold.mt-1 message]
        [:div.mt-1 [inspect data]]])
     via))
   [:div.py-6.overflow-x-auto
    [:table.w-full
     (into [:tbody]
           (map (fn [[call _x file line]]
                  [:tr.hover:bg-red-100.leading-tight
                   [:td.text-right.px-6 file ":"]
                   [:td.text-right.pr-6 line]
                   [:td.py-1.pr-6 call]]))
           trace)]]])

(defn render-throwable [ex]
  (html [throwable-view ex]))

(defn render-tagged-value
  ([tag value] (render-tagged-value {:space? true} tag value))
  ([{:keys [space?]} tag value]
   [:span.inspected-value.whitespace-nowrap
    [:span.cmt-meta tag] (when space? nbsp) value]))

(defn normalize-viewer-meta [x]
  (if-let [viewer (-> x meta :nextjournal/viewer)]
    (viewer/with-viewer ({:html html-viewer
                          :reagent reagent-viewer} viewer viewer) x)
    x))

(defonce !doc (ratom/atom nil))
(defonce !error (ratom/atom nil))
(defonce !viewers viewer/!viewers)

(defn set-viewers! [scope viewers]
  #_(js/console.log :set-viewers! {:scope scope :viewers viewers})
  (swap! !viewers assoc scope (vec viewers))
  'set-viewers!)

(declare default-viewers)

(defn render-with-viewer [opts viewer value]
  #_(js/console.log :render-with-viewer {:value value :viewer viewer :opts opts})
  (cond (or (fn? viewer) (viewer/viewer-fn? viewer))
        (viewer value opts)

        (and (map? viewer) (:render-fn viewer))
        (render-with-viewer opts (:render-fn viewer) value)

        #_#_ ;; TODO: maybe bring this back
        (keyword? viewer)
        (if-let [{:keys [fetch-opts render-fn]} (viewer/find-named-viewer viewers viewer)]
          (if-not render-fn
            (html (error-badge "no render function for viewer named " (str viewer)))
            (render-fn value (assoc opts :fetch-opts fetch-opts)))
          (html (error-badge "cannot find viewer named " (str viewer))))

        :else
        (html (error-badge "unusable viewer `" (pr-str viewer) "`, value `" (pr-str value) "`"))))

(defn valid-react-element? [x] (react/isValidElement x))

(defn inspect-presented
  ([x]
   (r/with-let [!expanded-at (r/atom (:nextjournal/expanded-at x))]
     [inspect-presented {:!expanded-at !expanded-at} x]))
  ([opts x]
   (if (valid-react-element? x)
     x
     (let [value (viewer/->value x)
           viewer (viewer/->viewer x)]
       #_(prn :inspect value :valid-element? (react/isValidElement value) :viewer (viewer/->viewer x))

       ;; each view function must be called in its own 'functional component' so that it gets its own hook state.
       ;; When using ^{:key viewer} we get duplicate keys in homogenous collections so also add idx key
       ;; TODO: clarify if this is ok.
       ^{:key (str viewer (:idx opts))}
       [inspect-presented
        (merge opts {:viewer viewer} (:nextjournal/opts x))
        viewer
        value])))
  ([opts viewer value]
   (let [x (render-with-viewer opts viewer value)]
     (if (valid-react-element? x)
       x
       (inspect-presented opts x)))))

(defn in-process-fetch [value opts]
  (.resolve js/Promise (viewer/present value opts)))

(defn inspect [value]
  (r/with-let [!state (r/atom nil)]
    (when (not= (:value @!state) value)
      (swap! !state assoc :value value :desc (viewer/present value)))
    [view-context/provide {:fetch-fn (fn [fetch-opts]
                                       (.then (in-process-fetch value fetch-opts)
                                              (fn [more]
                                                (swap! !state update :desc viewer/merge-presentations more fetch-opts))))}
     [inspect-presented (:desc @!state)]]))

(defn root []
  [:<>
   [inspect-presented @!doc]
   (when @!error
     [:div.fixed.top-0.left-0.w-full.h-full
      [inspect-presented @!error]])])

(declare mount)

(defn ^:export set-state [{:as state :keys [doc error remount?]}]
  (when remount?
    (swap! !eval-counter inc))
  (when (contains? state :doc)
    (reset! !doc doc))
  (reset! !error error)
  (when-let [title (and (exists? js/document) (-> doc viewer/->value :title))]
    (set! (.-title js/document) title)))

(defn ^:export ^:dev/after-load mount []
  (when-let [el (and (exists? js/document) (js/document.getElementById "clerk"))]
    #_(rdom/unmount-component-at-node el)
    (rdom/render [root] el)))

(defn clerk-eval [form]
  (.ws_send ^js goog/global (pr-str form)))

(defn render-katex [tex-string {:keys [inline?]}]
  (html (katex/to-html-string tex-string (j/obj :displayMode (not inline?)))))

(defn html-render [markup]
  (r/as-element
   (if (string? markup)
     [:span {:dangerouslySetInnerHTML {:__html markup}}]
     markup)))

(def html-viewer
  {:render-fn html-render})

(def html
  (partial viewer/with-viewer html-viewer))

(defn render-reagent [x]
  (r/as-element (cond-> x (fn? x) vector)))

;; TODO: remove
(def reagent-viewer render-reagent)

(defn use-promise
  "React hook which resolves a promise and handles errors."
  [p]
  (let [handle-error (use-handle-error)
        [v v!] (react/useState)]
    (react/useEffect (fn [] (-> p
                               (.then #(v! (constantly %)))
                               (.catch handle-error))))
    v))

(defn ^js use-d3-require [package]
  (let [p (react/useMemo #(apply d3-require/require
                                 (cond-> package
                                   (string? package)
                                   list))
                         #js[(str package)])]
    (use-promise p)))

(defn with-d3-require [{:keys [package loading-view]
                        :or {loading-view default-loading-view}} f]
  (if-let [package (use-d3-require package)]
    (f package)
    loading-view))

(defn render-vega-lite [value]
  (let [handle-error (use-handle-error)
        vega-embed (use-d3-require "vega-embed@6.11.1")
        ref-fn (react/useCallback #(when %
                                     (-> (.embed vega-embed % (clj->js (dissoc value :embed/opts)) (clj->js (:embed/opts value {})))
                                         (.catch handle-error)))
                                  #js[value vega-embed])]
    (when value
      (html (if vega-embed
              [:div.overflow-x-auto
               [:div.vega-lite {:ref ref-fn}]]
              default-loading-view)))))

(defn render-plotly [value]
  (let [plotly (use-d3-require "plotly.js-dist@2.15.1")
        ref-fn (react/useCallback #(when %
                                     (.newPlot plotly % (clj->js value)))
                                  #js[value plotly])]
    (when value
      (html
       (if plotly
         [:div.overflow-x-auto
          [:div.plotly {:ref ref-fn}]]
         default-loading-view)))))

(def render-mathjax (comp normalize-viewer-meta mathjax/viewer))
(def render-code (comp normalize-viewer-meta code/viewer))

(def expand-icon
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :width 12 :height 12}
   [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(defn render-folded-code [code-string]
  (r/with-let [!hidden? (r/atom true)]
    (html (if @!hidden?
            [:div.relative.pl-12.font-sans.text-slate-400.cursor-pointer.flex.overflow-y-hidden.group
             [:span.hover:text-slate-500
              {:class "text-[10px]"
               :on-click #(swap! !hidden? not)}
              "show code"]
             #_#_#_[:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.hover:text-slate-500
                    {:class "text-[10px]"}
                    "hide result"]
             [:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.delay-75.hover:text-slate-500
              {:class "text-[10px]"}
              "cached in memory"]
             [:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.delay-150.hover:text-slate-500
              {:class "text-[10px]"}
              "evaluated in 0.2s"]]
            [:<>
             [:div.relative.pl-12.font-sans.text-slate-400.cursor-pointer.flex.overflow-y-hidden.group.mb-1
              [:span.hover:text-slate-500
               {:class "text-[10px]"
                :on-click #(swap! !hidden? not)}
               "hide code"]
              #_#_#_[:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.hover:text-slate-500
                     {:class "text-[10px]"}
                     "hide result"]
              [:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.delay-75.hover:text-slate-500
               {:class "text-[10px]"}
               "cached in memory"]
              [:span.ml-4.opacity-0.translate-y-full.group-hover:opacity-100.group-hover:translate-y-0.transition-all.delay-150.hover:text-slate-500
               {:class "text-[10px]"}
               "evaluated in 0.2s"]]
             [:div.viewer-code.mb-2.relative {:style {:margin-top 0}}
              [inspect-presented (render-code code-string)]]]))))


(defn url-for [{:as src :keys [blob-id]}]
  (if (string? src)
    src
    (str "/_blob/" blob-id (when-let [opts (seq (dissoc src :blob-id))]
                             (str "?" (opts->query opts))))))

