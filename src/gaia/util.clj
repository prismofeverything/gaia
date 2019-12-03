(ns gaia.util
  )

(defn map-vals
  "Map a function over a map's values."
  [f m]
  (reduce-kv (fn [m k v] (assoc m k (f v))) {} m))

(defn map-keys
  "Map a function over a map's keys."
  [f m]
  (reduce-kv (fn [m k v] (assoc m (f k) v)) {} m))
