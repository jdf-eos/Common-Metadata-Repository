(ns cmr.bootstrap.data.fingerprint
  "Functions to support updating variable fingerprint."
  (:require
    [clojure.core.async :as ca :refer [<!!]]
    [cmr.bootstrap.embedded-system-helper :as helper]
    [cmr.common.log :refer (debug info warn error)]
    [cmr.common.concepts :as concepts]
    [cmr.metadata-db.data.concepts :as db]
    [cmr.umm-spec.fingerprint-util :as fingerprint-util]))

(defn- fingerprint-variable
  "Update the fingerprint of the given variable if necessary."
  [db provider variable]
  (let [{:keys [concept-id revision-id deleted metadata]} variable]
    (when (not deleted)
      (let [old-fingerprint (get-in variable [:extra-fields :fingerprint])
            new-fingerprint (fingerprint-util/get-variable-fingerprint metadata)]
        (when (not= old-fingerprint new-fingerprint)
          (db/save-concept db
                           provider
                           (-> variable
                               (assoc :revision-id (inc revision-id))
                               (assoc-in [:extra-fields :fingerprint] new-fingerprint)
                               (dissoc :revision-date)))
          (info (format "Updated fingerprint for concept-id: %s" concept-id)))))))

(defn- fingerprint-variable-batch
  "Update the fingerprint of the given variable if necessary."
  [db provider variable-batch]
  (dorun
   (map #(fingerprint-variable db provider %) variable-batch)))

(def ^:private find-variables-sql-part1
  "Defines the beginning part of the string to construct find variables sql statement."
  (str "select a.* from metadata_db.cmr_variables a, "
       "(select concept_id, max(revision_id) rid from metadata_db.cmr_variables "))

(def ^:private find-variables-sql-part2
  "Defines the ending part of the string to construct find variables sql statement."
  (str "group by concept_id) b"
       " where a.concept_id = b.concept_id and a.revision_id = b.rid and a.deleted = 0"))

(defn- find-variables-sql
  "Returns the sql statement to find the latest variable concept revisions that are not deleted."
  ([]
   (str find-variables-sql-part1 find-variables-sql-part2))
  ([provider-id]
   (format "%s where provider_id='%s' %s"
           find-variables-sql-part1 provider-id find-variables-sql-part2)))

(defn- fingerprint-by-provider
  "Update the fingerprint of variables of the given provider if necessary."
  [system provider]
  (let [db (helper/get-metadata-db-db system)
        {:keys [provider-id]} provider
        params {:concept-type :variable
                :provider-id provider-id}
        variable-batches (db/find-concepts-in-batches-with-stmt db
                                                                provider
                                                                params
                                                                (find-variables-sql provider-id)
                                                                (:db-batch-size system))]
    (dorun
     (pmap #(fingerprint-variable-batch db provider %) variable-batches))))

(defn- fingerprint-by-provider-id
  "Update the fingerprint of variables of the given provider id if necessary."
  [system provider-id]
  (let [provider (helper/get-provider system provider-id)]
    (info "Updating fingerprint for variables of provider: " provider-id)
    (fingerprint-by-provider system provider)))

(defn- fingerprint-all-variables
  "Update the fingerprint of variables of the given provider if necessary."
  [system]
  (info "Update fingerprint for all variables")
  (doseq [provider (helper/get-providers system)]
    (fingerprint-by-provider system provider))
  (info "Updating fingerprint for all variables completed."))

(defn fingerprint-variables
  "Update the fingerprint of the variables specified by the given params if necessary."
  [system params]
  (if-let [provider (:provider params)]
    (fingerprint-by-provider-id system provider)
    (fingerprint-all-variables system)))

(defn fingerprint-by-id
  "Update the fingerprint of the given variable specified by its concept id if necessary."
  [system concept-id]
  (let [db (helper/get-metadata-db-db system)
        provider-id (concepts/concept-id->provider-id concept-id)
        provider (helper/get-provider system provider-id)
        variable (db/get-concept db :variable provider concept-id)]
    (fingerprint-variable db provider variable)))

(defn handle-fingerprint-requests
  "Begin listening for fingerprinting requests on the specified channel in the
  bootstrap system. Used by asynchronous processing."
  [system]
  (info "Starting background task for monitoring fingerprinting channels.")
  (let [core-async-dispatcher (:core-async-dispatcher system)]
    (let [channel (:fingerprint-channel core-async-dispatcher)]
      (ca/thread (while true
                   (try ; catch any errors and log them, but don't let the thread die
                     (let [{:keys [provider-id]} (<!! channel)]
                       (fingerprint-variables system {:provider provider-id}))
                     (catch Throwable e
                       (error e (.getMessage e)))))))))