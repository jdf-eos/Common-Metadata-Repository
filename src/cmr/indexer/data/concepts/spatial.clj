(ns cmr.indexer.data.concepts.spatial
  "Contains functions to convert spatial geometry into indexed attributes."
  (:require [cmr.spatial.derived :as d]

            ;; Must be required for derived calculations
            [cmr.spatial.geodetic-ring :as gr]
            [cmr.spatial.polygon :as p]
            [cmr.spatial.mbr :as mbr]
            [cmr.spatial.lr-binary-search :as lr]
            [cmr.spatial.serialize :as srl]
            [cmr.common.services.errors :as errors]
            [cmr.umm.spatial :as umm-s]))

(def temporary-supported-cartesian-types
  "A temporary list of the subset of spatial types that we support."
  #{cmr.spatial.mbr.Mbr cmr.spatial.point.Point cmr.spatial.polygon.Polygon})

(defn mbr->elastic-attribs
  [prefix mbr]
  (let [with-prefix #(->> %
                          name
                          (str prefix "-")
                          keyword)]
    {(with-prefix :west) (:west mbr)
     (with-prefix :north) (:north mbr)
     (with-prefix :east) (:east mbr)
     (with-prefix :south) (:south mbr)
     (with-prefix :crosses-antimeridian) (mbr/crosses-antimeridian? mbr)}))


(defn shapes->elastic-doc
  "Converts a spatial shapes into the nested elastic attributes"
  [shapes coordinate-system]
  (let [shapes (map d/calculate-derived
                    (map (partial umm-s/set-coordinate-system coordinate-system)
                         shapes))
        ords-info-map (srl/shapes->ords-info-map shapes)
        lrs (map srl/shape->lr shapes)
        ;; union mbrs to get one covering the whole area
        mbr (reduce mbr/union (map srl/shape->mbr shapes))
        ;; Choose the largest lr
        lr (->> lrs
                (sort-by mbr/percent-covering-world)
                reverse
                first)]
    (merge ords-info-map
           (mbr->elastic-attribs "mbr" mbr)
           (mbr->elastic-attribs "lr" lr))))


(defn spatial->elastic-docs
  "Converts the spatial area of the given catalog item to the elastic documents"
  [coordinate-system catalog-item]
  (when-let [geometries (get-in catalog-item [:spatial-coverage :geometries])]
    (shapes->elastic-doc geometries coordinate-system)))