(ns user
  "Loaded by `bb nrepl`. Kept small: (dev) loads the development namespace.")

(defn dev
  "Load the development namespace and switch to it."
  []
  (require 'dev)
  (in-ns 'dev))
