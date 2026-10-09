def log = new File(basedir, "build.log").text
assert log.contains("MD5") : "unit-test phase evidence was lost when prepare-agent ran again"
assert log.contains("merged from 2 JVMs")
