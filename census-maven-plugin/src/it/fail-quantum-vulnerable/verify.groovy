def log = new File(basedir, "build.log").text
assert log.contains("at or above 'quantum-vulnerable'")
assert log.contains("SHA256withRSA")
