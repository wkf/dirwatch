;; Copyright © 2013, JUXT. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Directory watcher"
      :author "Malcolm Sparks"
      :requires "JDK7"}
  juxt.dirwatch
  (:import (java.io File)
           (java.nio.file FileSystems StandardWatchEventKinds WatchService Path)
           (java.util.concurrent TimeUnit)
           (com.sun.nio.file SensitivityWatchEventModifier))

;; TODO: Implement a version that uses polling to emulate this functionality on JDK6 and below.

(defn ^:private register-path
  "Register a watch service with a filesystem path.

  When collect-children? is set, returns a list of artificial events for files seen during recursion"
  [^WatchService ws, ^Path path & [event-atom]]
  (.register path ws
             (into-array
              (type StandardWatchEventKinds/ENTRY_CREATE)
              [StandardWatchEventKinds/ENTRY_CREATE
               StandardWatchEventKinds/ENTRY_DELETE
               StandardWatchEventKinds/ENTRY_MODIFY])
         SensitivityWatchEventModifier/HIGH)
  (doseq [dir (.. path toAbsolutePath toFile listFiles)]
    (when (. dir isDirectory)
          (register-path ws (. dir toPath) event-atom))
    (when event-atom
          (swap! event-atom conj {:file dir, :count 1, :action :create}))))

(defn ^:private wait-for-events [ws f]
  (when ws ;; nil when this watcher is closed

    (let [k (.poll ws (long 5) TimeUnit/SECONDS #_(comment "We set a
    timeout because this allows us to (eventually) free up the thread
    after the watcher is closed."))]
      (when (and k (.isValid k))
        (doseq [ev (.pollEvents k) :when (not= (.kind ev)
                                               StandardWatchEventKinds/OVERFLOW)]
          (let [file (.toFile (.resolve (.watchable k) (.context ev)))]
            (f {:file file
                :count (.count ev)
                :action (get {StandardWatchEventKinds/ENTRY_CREATE :create
                              StandardWatchEventKinds/ENTRY_DELETE :delete
                              StandardWatchEventKinds/ENTRY_MODIFY :modify}
                             (.kind ev))})
            (when (and (= (.kind ev) StandardWatchEventKinds/ENTRY_CREATE)
                       (.isDirectory file))
              (let [artificial-events (atom (list))]
                (register-path ws (.toPath file) artificial-events)
                (doseq [event @artificial-events]
                  (f event))))))
        ;; Cancel a key if the reset fails, this may indicate the path no longer exists
        (when-not (. k reset) (. k cancel)))

      ;; Repeat ad-infinitum
      (send-off *agent* wait-for-events f)

      ;; Retain the watch service as the agent state.
      ws)))

(defn- continue-on-exception
  [f]
  (fn [x]
    (try
      (f x)
      (catch Throwable e
        (.printStackTrace e)))))

;; Here's how you might use watch-dir :-
;;
;; (watch-dir (io/file "/tmp") println)
(defn watch-dir
  "Watch a directory for changes, and call the function f when it
  does. Returns a watch (an agent) that is activley watching a
  directory. The implementation uses a capability since Java 7 that
  wraps inotify on Linux and equivalent mechanisms on other operating
  systems. The watcher returned by this function is a resource which
  should be closed with close-watcher."
  [f & files]
  (let [ws (.newWatchService (FileSystems/getDefault))
        f (continue-on-exception f)]
    (doseq [file files :when (.exists file)] (register-path ws (. file toPath)))
    (send-off (agent ws
                     :meta {::watcher true}
                     :error-handler (fn [ag ex]
                                      (.printStackTrace ex)
                                      (send-off ag wait-for-events f)))
              wait-for-events f)))

(defn close-watcher
  "Close an existing watcher and free up it's resources."
  [watcher]
  {:pre [(::watcher (meta watcher))]}
  (send-off watcher (fn [w]
                      (when w (.close w))
                      nil)))
