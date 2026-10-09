def log = new File(basedir, "build.log").text
assert log.contains("Tests were skipped (skipTests)")
