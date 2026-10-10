def log = new File(basedir, "build.log").text
assert !log.contains("Tests were skipped") : "-DskipITs was taken as proof that no tests ran"
assert log.contains("No Crypto Census data was produced although tests ran")
