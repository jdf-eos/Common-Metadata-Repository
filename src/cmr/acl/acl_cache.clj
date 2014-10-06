(ns cmr.acl.acl-cache
  "Maintains an in-memory local cache of ACLs that is refreshed via a background job. This keeps
  ACLs fresh available instantly for callers without any caller having to pay the price to fetch
  the acls."
  (:require [cmr.common.services.errors :as errors]
            [clj-time.core :as t]
            [cmr.common.time-keeper :as tk]
            [cmr.common.jobs :refer [defjob]]
            [cmr.transmit.echo.acls :as echo-acls]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.cache :as cache]
            [clojure.core.cache :as cc]))

(def acl-cache-key
  "The key used to store the acl cache in the system cache map."
  :acls)

(defn create-acl-cache
  "Creates a new empty ACL cache."
  []
  (cache/create-cache))

(defn- context->acl-cache
  "Gets the acl cache from the context"
  [context]
  (get-in context [:system :caches :acls]))

(defn reset
  "Resets the cache back to it's initial state"
  [context]
  (-> context
      context->acl-cache
      (cache/reset-cache)))

(defn refresh-acl-cache
  "Refreshes the acls stored in the cache. This should be called from a background job on a timer
  to keep the cache fresh. This will throw an exception if there is a problem fetching ACLs. The
  caller is responsible for catching and logging the exception."
  [context]
  (let [acl-cache (context->acl-cache context)]
    (cache/set-cache!
      acl-cache
      (cc/basic-cache-factory
        {:acls (echo-acls/get-acls-by-type context "CATALOG_ITEM")}))))

(defn get-acls
  "Gets the current cached acls."
  [context]
  (let [acl-cache (context->acl-cache context)]
    (info "CACHE....." acl-cache)
    (cache/cache-lookup
      acl-cache
      :acls
      (fn []
        (echo-acls/get-acls-by-type context "CATALOG_ITEM")))))

(defjob RefreshAclCacheJob
  [ctx system]
  (refresh-acl-cache {:system system}))

(defn refresh-acl-cache-job
  [job-key]
  {:job-type RefreshAclCacheJob
   :job-key job-key
   :interval 3600})

