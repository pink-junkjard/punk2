(ns punk.ui.chrome.panel
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as a :refer [<!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [chromex.ext.devtools.inspected-window :as inspected]
            [hx.react :as hx]
            ["react-dom" :as rdom]
            [punk.ui.encode :as encode]
            [punk.ui.chrome.panel.js :as pjs]
            [punk.ui.core :as core]))

(def tab-id (inspected/get-tab-id))

(defonce connection (atom nil))

(defonce message-log (atom []))

(defonce tap-chan (a/chan))

(defonce error-chan (a/chan))

;; -- a message loop -----------------------------------------------------------

(defmulti route-message! (fn [[type _]] type))

(defmethod route-message! :default
  [message]
  (prn "PANEL: unknown message type" message)
  nil)

(defmethod route-message! :content-script/connect
  [_]
  (log "PANEL: installing tap")
  (inspected/eval pjs/add-tap!))

(defmethod route-message! :tab/message [[_ message]]
  (try
    (a/put! tap-chan (encode/read message))
    (catch js/Error e
      (js/console.log e)
      (a/put! error-chan e))))

(defn process-message! [message]
  (prn "PANEL: got message:" #_message)
  (route-message! message))

(defn run-message-loop! [message-channel]
  (log "PANEL: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      (swap! message-log conj message)
      (try
        (process-message! (encode/read message))
        (catch js/Error e
          (log "Error in message loop:" e)))
      (recur))
    (log "PANEL: leaving message loop")))

(defn connect-to-background-page! []
  (let [background-port (runtime/connect)]
    (post-message! background-port (encode/write [:devtool/connect tab-id]))
    (run-message-loop! background-port)
    background-port))

;; -- main entry point ---------------------------------------------------------

(defn init! []
  (log "PANEL: init")
  (when-not @connection
    (inspected/eval pjs/setup)
    (reset! connection (connect-to-background-page!)))
  (rdom/render (hx/f [core/Main {:tap-chan tap-chan
                                 :error-chan error-chan}])
               (.getElementById js/document "frame")))

(init!)
