(ns black-box-cli.ids-test
  (:require
   [black-box-cli.ids :as ids]
   [clojure.test :refer [deftest is testing]]))

(deftest a-clip-is-eight-base32-characters-of-the-path
  (is (= "r54qb74c" (ids/clip "media/2026/09/16/src/fx3/C5274.MP4")))
  (is (= "r54qb74c.3" (ids/line-id "media/2026/09/16/src/fx3/C5274.MP4" 3)))
  (is (re-matches #"[a-z2-7]{8}" (ids/clip "média/è/ü.MP4"))))

(deftest ids-parse-or-say-why-not
  (is (= {:clip "r54qb74c" :cue 3} (ids/parse "r54qb74c.3")))
  (is (= {:clip "r54qb74c" :cue 3} (ids/parse " R54QB74C.3 ")))
  (doseq [bad ["c12345" "x1" "r54qb7.3" "r54qb74c" "r54qb74c.0" "r54qb74c.03" "r54qb71c.3" "r54qb74c.3.1" ""]]
    (testing bad
      (is (= "bad-id" (try (ids/parse bad) nil
                           (catch clojure.lang.ExceptionInfo e
                             (get-in (ex-data e) [:black-box-cli.out/error :code]))))))))
