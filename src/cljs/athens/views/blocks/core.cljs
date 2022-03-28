(ns athens.views.blocks.core
  (:require
   ["/components/Block/components/Anchor"   :refer [Anchor]]
   ["/components/Block/components/SlashMenu" :refer [SlashMenu]]
   ["/components/Block/components/Toggle"   :refer [Toggle]]
   ["@chakra-ui/react" :refer [Box Button Breadcrumb BreadcrumbItem BreadcrumbLink HStack]]
   [athens.common.logging                   :as log]
   [athens.db                               :as db]
   [athens.electron.images                  :as images]
   [athens.electron.utils                   :as electron.utils]
   [athens.events.selection                 :as select-events]
   [athens.parse-renderer                   :as parse-renderer]
   [athens.reactive                         :as reactive]
   [athens.router                           :as router]
   [athens.self-hosted.presence.views       :as presence]
   [athens.subs.selection                   :as select-subs]
   [athens.util                             :as util :refer [mouse-offset vertical-center specter-recursive-path]]
   [athens.views.blocks.autocomplete-search :as autocomplete-search]
   [athens.views.blocks.autocomplete-slash  :as autocomplete-slash]
   [athens.views.blocks.bullet              :refer [bullet-drag-start bullet-drag-end]]
   [athens.views.blocks.content             :as content]
   [athens.views.blocks.context-menu        :refer [handle-copy-unformatted handle-copy-refs]]
   [athens.views.blocks.drop-area-indicator :as drop-area-indicator]
   [athens.views.references                 :refer [reference-group reference-block]]
   [com.rpl.specter                         :as s]
   [goog.functions                          :as gfns]
   [re-frame.core                           :as rf]
   [reagent.core                            :as r]))


;; Styles
;;
;; Blocks use Em units in many places rather than Rem units because
;; blocks need to scale with their container: sidebar blocks are
;; smaller than main content blocks, for instance.


(def block-container-inner-style
  {"&.show-tree-indicator:before" {:content    "''"
                                   :position   "absolute"
                                   :width      "1px"
                                   :left       "calc(1.375em + 1px)"
                                   :top        "2em"
                                   :bottom     "0"
                                   :opacity    "0"
                                   :transform  "translateX(50%)"
                                   :transition "background-color 0.2s ease-in-out, opacity 0.2s ease-in-out"
                                   :background "separator.divider"}
   "&:hover, &:focus-within.show-tree-indicator:before" {:opacity 1}
   "&:after" {:content        "''"
              :zIndex        -1
              :position       "absolute"
              :top            "0.75px"
              :right          0
              :bottom         "0.75px"
              :left           0
              :opacity        0
              :pointerEvents "none"
              :borderRadius "0.25rem"
              :transition "opacity 0.075s ease"
              :background "link"}
   "&.is-selected:after" {:opacity 1}
   "&.is-presence .block-content" {:padding-right "1rem"}
   ".user-avatar" {:position "absolute"
                   :left "4px"
                   :top "4px"}
   ".block-body" {:display               "grid"
                  :gridTemplateColumns "1em 1em 1fr auto"
                  :gridTemplateRows    "0 1fr 0"
                  :gridTemplateAreas   "
                                      'above above above above'
                                      'toggle bullet content refs'
                                      'below below below below'"
                  :borderRadius         "0.5rem"
                  :position              "relative"}
   "&:hover > .block-toggle, 
     &:focus-within > .block-toggle" {:opacity "1"}
   "button.block-edit-toggle" {:position   "absolute"
                               :appearance "none"
                               :width      "100%"
                               :background "none"
                               :border     0
                               :cursor     "text"
                               :display    "block"
                               :z-index    1
                               :top        0
                               :right      0
                               :bottom     0
                               :left       0}
   ".block-embed" {:borderRadius "sm"
                   :sx {"--block-surface-color" "background.basement"}
                   :bg "background.basement"
                   ".block-container" {:marginLeft 0.5}}
   ".block-content" {:gridArea  "content"
                     :minHeight "1.5em"}
   "&.is-linked-ref" {:bg "background-attic"}
   ".block-container" {:marginLeft "2rem"
                       :gridArea "body"}})


;; Inline refs

;; block-el depends on inline-linked-refs-el, which in turn depends on block-el
;; It would be nicer to have inline refs code in a different file, but it's
;; much easier to resolve the circular dependency if they are on the same one.
(declare block-el)


(defn ref-comp
  [block parent-state]
  (let [orig-uid        (:block/uid block)
        state           (r/cursor parent-state [:inline-refs/states orig-uid])
        has-children?   (-> block :block/children boolean)
        parents         (cond-> (:block/parents block)
                          ;; If the ref has children, move it to breadcrumbs and show children.
                          has-children? (conj block))
        ;; Reset state on parent each time the component is created.
        ;; To clear state, open/close the inline refs.
        _               (reset! state {:block     block
                                       :embed-id  (random-uuid)
                                       :open?     true
                                       :parents   parents
                                       :focus?    (not has-children?)})
        linked-ref-data {:linked-ref     true
                         :initial-open   false
                         :linked-ref-uid (:block/uid block)
                         :parent-uids    (set (map :block/uid (:block/parents block)))}]
    (fn [_]
      (let [{:keys [block parents embed-id]} @state
            block (reactive/get-reactive-block-document (:db/id block))]
        [:<>
         [:> HStack
          [:> Toggle {:isOpen (:open? @state)
                      :on-click (fn [e]
                                  (.. e stopPropagation)
                                  (swap! state update :open? not))}]

          [:> Breadcrumb {:fontSize "0.7em" :pl 6}
           (doall
             (for [{:keys [node/title block/string block/uid] :as breadcrumb-block}
                   (if (or (:open? @state) (not (:focus? @state)))
                     parents
                     (conj parents block))]
               [:> BreadcrumbItem {:key (str "breadcrumb-" uid)}
               [:> BreadcrumbLink {:onClick #(let [new-B (db/get-block [:block/uid uid])
                                                   new-P (concat
                                                          (take-while (fn [b] (not= (:block/uid b) uid)) parents)
                                                          [breadcrumb-block])]
                                               (.. % stopPropagation)
                                               (swap! state assoc :block new-B :parents new-P :focus? false))}
                [parse-renderer/parse-and-render (or title string) uid]]]))]]

         (when (:open? @state)
           (if (:focus? @state)

             ;; Display the single child block only when focusing.
             ;; This is the default behaviour for a ref without children, for brevity.
             [:div.block-embed
              [block-el
               (util/recursively-modify-block-for-embed block embed-id)
               linked-ref-data
               {:block-embed? true}]]


             ;; Otherwise display children of the parent directly.
             (for [child (:block/children block)]
               [:<> {:key (:db/id child)}
                [block-el
                 (util/recursively-modify-block-for-embed child embed-id)
                 linked-ref-data
                 {:block-embed? true}]])))]))))


(defn inline-linked-refs-el
  [state uid]
  (let [refs (reactive/get-reactive-linked-references [:block/uid uid])]
    (when (not-empty refs)
      [:> Box {:as "section"
               :key "Inline Linked References"
               :zIndex 2
               :ml 6
               :pl 2
               :borderRadius "10px solid red"
               :background "background.basement"}
        (doall
         (for [[group-title group] refs]
           [reference-group {:title group-title
                             :key (str "group-" group-title)}
            (doall
             (for [block' group]
               [reference-block {:key (str "ref-" (:block/uid block'))}
                [ref-comp block' state]]))]))])))


;; Components

(defn block-refs-count-el
  [count click-fn active?]
   [:> Button {:gridArea "refs"
               :size "xs"
               :ml "1em"
               :mt 1
               :mr 1
               :zIndex 10
               :visibility (if (pos? count) "visible" "hidden")
               :isActive active?
               :onClick (fn [e]
                          (.. e stopPropagation)
                          (click-fn e))}
    count])


(defn block-drag-over
  "If block or ancestor has CSS dragging class, do not show drop indicator; do not allow block to drop onto itself.
  If above midpoint, show drop indicator above block.
  If no children and over X pixels from the left, show child drop indicator.
  If below midpoint, show drop indicator below."
  [e block state]
  (.. e preventDefault)
  (.. e stopPropagation)
  (let [{:block/keys [children
                      uid
                      open]} block
        closest-container    (.. e -target (closest ".block-container"))
        {:keys [x y]}        (mouse-offset e closest-container)
        middle-y             (vertical-center closest-container)
        dragging-ancestor    (.. e -target (closest ".dragging"))
        dragging?            dragging-ancestor
        is-selected?         @(rf/subscribe [::select-subs/selected? uid])
        target               (cond
                               dragging?           nil
                               is-selected?        nil
                               (or (neg? y)
                                   (< y middle-y))         :before
                               (and (< middle-y y)
                                    (> 50 x))              :after
                               (or (not open)
                                   (and (empty? children)
                                        (< 50 x)))         :first)]
    (when target
      (swap! state assoc :drag-target target))))


(defn drop-bullet
  "Terminology :
    - source-uid        : The block which is being dropped.
    - target-uid        : The block on which source is being dropped.
    - drag-target       : Represents where the block is being dragged. It can be `:first` meaning
                          dragged as a child, `:before` meaning the source block is dropped above the
                          target block, `:after` meaning the source block is dropped below the target block.
    - action-allowed    : There can be 2 types of actions.
        - `link` action : When a block is DnD by dragging a bullet while
                         `shift` key is pressed to create a block link.
        - `move` action : When a block is DnD to other part of Athens page. "

  [source-uid target-uid drag-target action-allowed]
  (let [move-action? (= action-allowed "move")
        event         [(if move-action?
                         :block/move
                         :block/link)
                       {:source-uid source-uid
                        :target-uid target-uid
                        :target-rel drag-target}]]
    (log/debug "drop-bullet" (pr-str {:source-uid     source-uid
                                      :target-uid     target-uid
                                      :drag-target    drag-target
                                      :action-allowed action-allowed
                                      :event          event}))
    (rf/dispatch event)))


(defn drop-bullet-multi
  "
  Terminology :
    - source-uids       : Uids of the blocks which are being dropped
    - target-uid        : Uid of the block on which source is being dropped"
  [source-uids target-uid drag-target]
  (let [source-uids          (mapv (comp first db/uid-and-embed-id) source-uids)
        target-uid           (first (db/uid-and-embed-id target-uid))
        event                (if (= drag-target :first)
                               [:drop-multi/child {:source-uids source-uids
                                                   :target-uid  target-uid}]
                               [:drop-multi/sibling {:source-uids source-uids
                                                     :target-uid  target-uid
                                                     :drag-target drag-target}])]
    (rf/dispatch [::select-events/clear])
    (rf/dispatch event)))


(defn block-drop
  "Handle dom drop events, read more about drop events at:
  : https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API#Define_a_drop_zone"

  [e block state]
  (.. e stopPropagation)
  (let [{target-uid :block/uid} block
        [target-uid _]          (db/uid-and-embed-id target-uid)
        {:keys [drag-target]}   @state
        source-uid              (.. e -dataTransfer (getData "text/plain"))
        effect-allowed          (.. e -dataTransfer -effectAllowed)
        items                   (array-seq (.. e -dataTransfer -items))
        item                    (first items)
        datatype                (.. item -type)
        img-regex               #"(?i)^image/(p?jpeg|gif|png)$"
        valid-text-drop         (and (not (nil? drag-target))
                                     (not= source-uid target-uid)
                                     (or (= effect-allowed "link")
                                         (= effect-allowed "move")))
        selected-items           @(rf/subscribe [::select-subs/items])]

    (cond
      (re-find img-regex datatype) (when electron.utils/electron?
                                     (images/dnd-image target-uid drag-target item (second (re-find img-regex datatype))))
      (re-find #"text/plain" datatype) (when valid-text-drop
                                         (if (empty? selected-items)
                                           (drop-bullet source-uid target-uid drag-target effect-allowed)
                                           (drop-bullet-multi selected-items target-uid drag-target))))

    (rf/dispatch [:mouse-down/unset])
    (swap! state assoc :drag-target nil)))


(defn block-drag-leave
  "When mouse leaves block, remove any drop area indicator.
  Ignore if target-uid and related-uid are the same — user went over a child component and we don't want flicker."
  [e block state]
  (.. e preventDefault)
  (.. e stopPropagation)
  (let [{target-uid :block/uid} block
        related-uid (util/get-dataset-uid (.. e -relatedTarget))]
    (when-not (= related-uid target-uid)
      ;; (prn target-uid related-uid  "LEAVE")
      (swap! state assoc :drag-target nil))))


(defn toggle
  [block-uid open]
  (rf/dispatch [:block/open {:block-uid block-uid
                             :open?     open}]))


(defn block-el
  "Two checks dec to make sure block is open or not: children exist and :block/open bool"
  ([block]
   [block-el block {:linked-ref false} {}])
  ([block linked-ref-data]
   [block-el block linked-ref-data {}])
  ([block linked-ref-data _opts]
   (let [{:keys [linked-ref initial-open linked-ref-uid parent-uids]} linked-ref-data
         {:block/keys [uid original-uid]} block
         state (r/atom {:string/local       nil
                        :string/previous    nil
                        ;; one of #{:page :block :slash :hashtag :template}
                        :search/type        nil
                        :search/results     nil
                        :search/query       nil
                        :search/index       nil
                        :dragging           false
                        :drag-target        nil
                        :last-keydown       nil
                        :context-menu/x     nil
                        :context-menu/y     nil
                        :context-menu/show  false
                        :caret-position     nil
                        :show-editable-dom  false
                        :linked-ref/open    (or (false? linked-ref) initial-open)
                        :inline-refs/open   false
                        :inline-refs/states {}
                        :block/uid          uid})
         save-fn #(db/transact-state-for-uid (or original-uid uid) state)
         idle-fn (gfns/debounce save-fn 2000)]
     (swap! state assoc
            :string/save-fn save-fn
            :string/idle-fn idle-fn)

     (fn [block linked-ref-data opts]
       (let [ident                [:block/uid (or original-uid uid)]
             {:block/keys [uid
                           string
                           open
                           children
                           _refs]} (merge (reactive/get-reactive-block-document ident) block)
             children-uids         (set (map :block/uid children))
             uid-sanitized-block   (s/transform
                                     (specter-recursive-path #(contains? % :block/uid))
                                     (fn [{:block/keys [original-uid uid] :as block}]
                                       (assoc block :block/uid (or original-uid uid)))
                                     block)
             {:keys [dragging]}    @state
             is-editing            @(rf/subscribe [:editing/is-editing uid])
             is-selected           @(rf/subscribe [::select-subs/selected? uid])
             present-user          @(rf/subscribe [:presence/has-presence uid])
             is-presence           (seq present-user)]

         ;; (prn uid is-selected)

         ;; If datascript string value does not equal local value, overwrite local value.
         ;; Write on initialization
         ;; Write also from backspace, which can join bottom block's contents to top the block.
         (when (not= string (:string/previous @state))
           (swap! state assoc :string/previous string :string/local string))

         [:> Box {:display         "flex"
                  :line-height     "2em"
                  :position        "relative"
                  :border-radius   "0.125rem"
                  :justify-content "flex-start"
                  :flex-direction  "column"
                  :background "var(--block-surface-color)"
                  :opacity (if dragging 0.5 1.0)
                  :sx (merge block-container-inner-style
                             {"--block-surface-color" "background.floor"})
                  :class ["block-container"
                          (when (and dragging (not is-selected)) "dragging")
                          (when is-editing "is-editing")
                          (when is-selected "is-selected")
                          (when (and (seq children) open) "show-tree-indicator")
                          (when (and (false? initial-open) (= uid linked-ref-uid)) "is-linked-ref")
                          (when is-presence "is-presence")]
                  :data-uid          uid
                  ;; need to know children for selection resolution
                  :data-childrenuids children-uids
                  ;; :show-editable-dom allows us to render the editing elements (like the textarea)
                  ;; even when not editing this block. When true, clicking the block content will pass
                  ;; the clicks down to the underlying textarea. The textarea is expensive to render,
                  ;; so we avoid rendering it when it's not needed.
                  :on-mouse-enter    #(swap! state assoc :show-editable-dom true)
                  :on-mouse-leave    #(swap! state assoc :show-editable-dom false)
                  :on-drag-over      (fn [e] (block-drag-over e block state))
                  :on-drag-leave     (fn [e] (block-drag-leave e block state))
                  :on-drop           (fn [e] (block-drop e block state))}

          (when (= (:drag-target @state) :before) [drop-area-indicator/drop-area-indicator {:placement "above"}])

          [:div.block-body
           (when (seq children)
             [:> Toggle {:isOpen (if (or (and (true? linked-ref) (:linked-ref/open @state))
                                         (and (false? linked-ref) open))
                                   true
                                   false)
                         :onClick (fn [e]
                                    (.. e stopPropagation)
                                    (if (true? linked-ref)
                                      (swap! state update :linked-ref/open not)
                                      (toggle uid (not open))))}])
           [:> Anchor {:isClosedWithChildren (when (and (seq children)
                                                        (or (and (true? linked-ref) (not (:linked-ref/open @state)))
                                                            (and (false? linked-ref) (not open))))
                                               "closed-with-children")
                       :block block
                       :shouldShowDebugDetails (util/re-frame-10x-open?)
                       :onCopyRefs #(handle-copy-refs nil uid state)
                       :onCopyUnformatted #(handle-copy-unformatted uid state)
                       :onClick        (fn [e]
                                         (let [shift? (.-shiftKey e)]
                                           (rf/dispatch [:reporting/navigation {:source :block-bullet
                                                                                :target :block
                                                                                :pane   (if shift?
                                                                                          :right-pane
                                                                                          :main-pane)}])
                                           (router/navigate-uid uid e)))
                       ;; :on-context-menu (fn [e] (context-menu/bullet-context-menu e uid state))
                       :on-drag-start   (fn [e] (bullet-drag-start e uid state))
                       :on-drag-end     (fn [e] (bullet-drag-end e uid state))}]
           [content/block-content-el block state]

           [presence/inline-presence-el uid]

           (when (and (> (count _refs) 0) (not= :block-embed? opts))
             [block-refs-count-el
              (count _refs)
              (fn [e]
                (if (.. e -shiftKey)
                  (rf/dispatch [:right-sidebar/open-item uid])
                  (swap! state update :inline-refs/open not)))
              (:inline-refs/open @state)])]


          [autocomplete-search/inline-search-el block state]
          [autocomplete-slash/slash-menu-el block state]

          ;; Inline refs
          (when (and (> (count _refs) 0)
                     (not= :block-embed? opts)
                     (:inline-refs/open @state))
            [inline-linked-refs-el state uid])

          ;; Children
          (when (and (seq children)
                     (or (and (true? linked-ref) (:linked-ref/open @state))
                         (and (false? linked-ref) open)))
            (for [child children]
              [:<> {:key (:db/id child)}
               [block-el child
                (assoc linked-ref-data :initial-open (contains? parent-uids (:block/uid child)))
                opts]]))

          (when (= (:drag-target @state) :first) [drop-area-indicator/drop-area-indicator {:placement "below" :child? true}])
          (when (= (:drag-target @state) :after) [drop-area-indicator/drop-area-indicator {:placement "below"}])])))))

