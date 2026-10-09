def log = new File(basedir, "build.log").text
assert log.contains("No Crypto Census data was produced although tests ran") : "an unattached Failsafe run passed because of -DskipTests"
