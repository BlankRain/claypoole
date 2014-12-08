;; The Climate Corporation licenses this file to you under under the Apache
;; License, Version 2.0 (the "License"); you may not use this file except in
;; compliance with the License.  You may obtain a copy of the License at
;;
;;   http://www.apache.org/licenses/LICENSE-2.0
;;
;; See the NOTICE file distributed with this work for additional information
;; regarding copyright ownership.  Unless required by applicable law or agreed
;; to in writing, software distributed under the License is distributed on an
;; "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
;; or implied.  See the License for the specific language governing permissions
;; and limitations under the License.

(ns com.climate.claypoole.impl
  "Implementation helper functions for Claypoole."
  (:require
    [clojure.core :as core])
  (:import
    [clojure.lang
     IFn]
    [com.climate.claypoole.impl
     Prioritized
     PriorityThreadpoolImpl]
    [java.util
     Collection
     List]
    [java.util.concurrent
     ExecutionException
     Executors
     ExecutorService
     Future
     LinkedBlockingQueue
     ScheduledExecutorService
     ThreadFactory
     TimeoutException
     TimeUnit]))


(defn binding-conveyor-fn
  "Like clojure.core/binding-conveyor-fn for resetting bindings to run a
  function in another thread."
  [f]
  (let [frame (clojure.lang.Var/cloneThreadBindingFrame)]
    (with-meta
      (fn []
        (clojure.lang.Var/resetThreadBindingFrame frame)
        (f))
      (meta f))))

(defn deref-future
  "Like clojure.core/deref-future."
  ([^Future fut]
   (.get fut))
  ([^Future fut timeout-ms timeout-val]
   (try (.get fut timeout-ms TimeUnit/MILLISECONDS)
     (catch TimeoutException e
       timeout-val))))

(defn dummy-future-call
  "A dummy future-call that runs in serial and returns a future containing the
  result."
  [f]
  (let [result (f)]
    (reify
      clojure.lang.IDeref
      (deref [_] result)
      clojure.lang.IBlockingDeref
      (deref [_ timeout-ms timeout-val] result)
      clojure.lang.IPending
      (isRealized [_] true)
      Future
      (get [_] result)
      (get [_ timeout unit] result)
      (isCancelled [_] false)
      (isDone [_] true)
      (cancel [_ interrupt?] false))))

(defn validate-future-pool
  "Verify that a threadpool is a valid pool for a future."
  [pool]
  (when-not (or (= :serial pool)
                (= :builtin pool)
                (instance? ExecutorService pool))
    (throw (IllegalArgumentException.
             (format
               (str "Theadpool futures require a threadpool, :builtin, or "
                    ":serial, not %s.") pool)))))

(defonce ^{:doc "The previously-used threadpool ID."}
  threadpool-id
  ;; Start at -1 so we can just use the return value of (swap! inc).
  (atom -1))

(defn default-threadpool-name
  "The default name for threads in a threadpool. Gives each threadpool a
  unique ID via threadpool-id."
  []
  (format "claypoole-%d" (swap! threadpool-id inc)))

(defn apply-map
  "Apply a function that takes keyword arguments to a map of arguments."
  [f & args]
  (let [args (drop-last args)
        arg-map (last args)]
    (apply f (concat args (mapcat identity arg-map)))))

(defn thread-factory
  "Create a ThreadFactory with keyword options including thread daemon status
  :daemon, the thread name format :name (a string for format with one integer),
  and a thread priority :thread-priority."
  ^ThreadFactory [& {:keys [daemon thread-priority] pool-name :name
                     :or {daemon true}}]
  (let [daemon* (boolean daemon)
        pool-name* (or pool-name (default-threadpool-name))
        thread-priority* (or thread-priority
                             (.getPriority (Thread/currentThread)))]
    (let [default-factory (Executors/defaultThreadFactory)
          ;; The previously-used thread ID. Start at -1 so we can just use the
          ;; return value of (swap! inc).
          thread-id (atom -1)]
      (reify ThreadFactory
        (^Thread newThread [_ ^Runnable r]
          (doto (.newThread default-factory r)
            (.setDaemon daemon*)
            (.setName (str pool-name* "-" (swap! thread-id inc)))
            (.setPriority thread-priority*)))))))

(defn unchunk
  "Takes a seqable and returns a lazy sequence that is maximally lazy.

  Based on http://stackoverflow.com/questions/3407876/how-do-i-avoid-clojures-chunking-behavior-for-lazy-seqs-that-i-want-to-short-ci"
  [s]
  (lazy-seq
    (when-let [s (seq s)]
      (cons (first s)
            (unchunk (rest s))))))

(defn threadpool
  "Make a threadpool. It should be shutdown when no longer needed.

  See docs in com.climate.claypoole/threadpool."
  ^ScheduledExecutorService [n & args]
  ;; Return a ScheduledThreadPool rather than a FixedThreadPool because it's
  ;; the same thing with some bonus features.
  (Executors/newScheduledThreadPool n (apply thread-factory args)))

(defn- prioritize
  "Apply a priority function to a task.

  Note that this re-throws all priority-fn exceptions as ExecutionExceptions.
  That shouldn't mess anything up because the caller re-throws it as an
  ExecutionException anyway.

  For simplicity, prioritize reifies both Callable and Runnable, rather than
  having one prioritize function for each of those types. That means, for
  example, that if you prioritize a Runnable that is not also a Callable, you
  might want to cast the result to Runnable or otherwise use it carefully."
  [task, ^IFn priority-fn]
  (let [priority (try
                   (long (apply priority-fn (-> task meta :args)))
                   (catch Exception e
                     (throw (ExecutionException.
                              "Priority function exception" e))))]
    (reify
      Callable
      (call [_] (.call ^Callable task))
      Runnable
      (run [_] (.run ^Runnable task))
      Prioritized
      (getPriority [_] priority))))

;; A Threadpool that applies a priority function to tasks and uses a
;; PriorityThreadpoolImpl to run them.
(deftype PriorityThreadpool [^PriorityThreadpoolImpl pool, ^IFn priority-fn]
  ExecutorService
  (^boolean awaitTermination [_, ^long timeout, ^TimeUnit unit]
    (.awaitTermination pool timeout unit))
  (^List invokeAll [_, ^Collection tasks]
    (.invokeAll pool (map #(prioritize % priority-fn) tasks)))
  (^List invokeAll [_, ^Collection tasks, ^long timeout, ^TimeUnit unit]
    (.invokeAll pool (map #(prioritize % priority-fn) tasks) timeout unit))
  (^Object invokeAny [_, ^Collection tasks]
    (.invokeAny pool (map #(prioritize % priority-fn) tasks)))
  (^Object invokeAny [_, ^Collection tasks, ^long timeout, ^TimeUnit unit]
    (.invokeAny pool (map #(prioritize % priority-fn) tasks) timeout unit))
  (^boolean isShutdown [_]
    (.isShutdown pool))
  (^boolean isTerminated [_]
    (.isTerminated pool))
  (shutdown [_]
    (.shutdown pool))
  (^List shutdownNow [_]
    (.shutdownNow pool))
  (^Future submit [_, ^Runnable task]
    (.submit pool ^Runnable (prioritize task priority-fn)))
  (^Future submit [_, ^Runnable task, ^Object result]
    (.submit pool ^Runnable (prioritize task priority-fn) result))
  (^Future submit [_ ^Callable task]
    (.submit pool ^Callable (prioritize task priority-fn))))

(defn ->threadpool
  "Convert the argument into a threadpool, leaving the special keyword :serial
  alone.

  Returns [created? threadpool], where created? indicates whether a new
  threadpool was instantiated."
  [arg]
  (cond
    (instance? ExecutorService arg) [false arg]
    (integer? arg) [true (threadpool arg)]
    (= :builtin arg) [false clojure.lang.Agent/soloExecutor]
    (= :serial arg) [false :serial]
    :else (throw (IllegalArgumentException.
                   (format
                     (str "Claypoole functions require a threadpool, a "
                          "number, :builtin, or :serial, not %s.") arg)))))

(defn get-pool-size
  "If the pool has a max size, get that; else, return nil."
  [pool]
  (cond
    (instance? java.util.concurrent.ScheduledThreadPoolExecutor pool)
    (.getCorePoolSize ^java.util.concurrent.ScheduledThreadPoolExecutor pool)

    (instance? java.util.concurrent.ThreadPoolExecutor pool)
    (.getMaximumPoolSize ^java.util.concurrent.ThreadPoolExecutor pool)

    :else
    nil))

;; Queue-seq needs a unique item that, when seen in a queue, indicates that the
;; sequence has ended. It uses the private object end-marker, and uses
;; identical? to check against this object's (unique) memory address.
(let [end-marker (Object.)]

  (defn- queue-reader
    "Make a lazy sequence from a queue, stopping upon reading the unique
    end-marker object."
    [^LinkedBlockingQueue q]
    (lazy-seq
      (let [x (.take q)]
        (when-not (identical? x end-marker)
          (cons x (queue-reader q))))))

  (defn queue-seq
    "Create a queue and a lazy sequence that reads from that queue."
    []
    (let [q (LinkedBlockingQueue.)]
      [q (queue-reader q)]))

  (defn queue-seq-add!
    "Add an item to a queue (and its lazy sequence)."
    [^LinkedBlockingQueue q x]
    (.add q x))

  (defn queue-seq-end!
    "End a lazy sequence reading from a queue."
    [q]
    (queue-seq-add! q end-marker)))

(defn lazy-co-read
  "Zip s1 and s2, stopping when s1 stops. This helps avoid potential blocking
  when trying to read queue sequences.

  In particular, this will block:
    (map vector
         (range 10)
         (concat (range 10) (lazy-seq (deref (promise)))))
  even though we only can read 10 things. Lazy-co-read fixes that case by
  checking the first sequence first, so this will not block:
    (lazy-co-read
      (range 10)
      (concat (range 10) (lazy-seq (deref (promise)))))"
  [s1 s2]
  (lazy-seq (when-not (empty? s1)
              (cons [(first s1) (first s2)]
                    (lazy-co-read (rest s1) (rest s2))))))

(defn subseqs
  "Given a sequence s, return a lazy, non-head-holding sequence of
  (s (drop 1 s) (drop 2 s) ... '())"
  [s]
  (reductions (fn [l _] (rest l)) s s))
