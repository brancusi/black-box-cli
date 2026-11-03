(ns black-box-cli.tools-test
  "Finding the local tools: the ones Black Box bundles first, then the PATH."
  (:require
   [babashka.fs :as fs]
   [black-box-cli.tools :as tools]
   [clojure.test :refer [deftest is testing]]))

(defn- tool-in
  "A folder with an executable `name` in it."
  [name]
  (let [dir (fs/create-temp-dir {:prefix "bbcli-tools-"})
        f (fs/path dir name)]
    (spit (str f) "#!/bin/sh\n")
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    (str dir)))

(deftest bundled-tools-come-first
  (let [bundled (tool-in "ffmpeg")
        on-path (tool-in "ffmpeg")]
    (testing "$BLACKBOX_TOOLS before the PATH"
      (is (= (str (fs/path bundled "ffmpeg"))
             (tools/find-tool {"BLACKBOX_TOOLS" bundled "PATH" on-path} "ffmpeg"))))
    (testing "the PATH when the bundle hasn't got it"
      (is (= (str (fs/path on-path "ffmpeg"))
             (tools/find-tool {"BLACKBOX_TOOLS" (tool-in "ffprobe") "PATH" on-path} "ffmpeg"))))
    (testing "without $BLACKBOX_TOOLS, the PATH as before"
      (is (= (str (fs/path on-path "ffmpeg")) (tools/find-tool {"PATH" on-path} "ffmpeg"))))))
