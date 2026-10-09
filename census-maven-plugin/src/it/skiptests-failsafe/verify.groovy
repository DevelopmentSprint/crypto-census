def log = new File(basedir, "build.log").text
assert log.contains("at or above 'broken'") : "Failsafe evidence was ignored because of -DskipTests"
assert log.contains("MD5")
