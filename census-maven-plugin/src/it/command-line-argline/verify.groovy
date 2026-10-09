def log = new File(basedir, "build.log").text
assert log.contains("-DargLine on the command line replaces the Crypto Census agent option")
assert log.contains("No Crypto Census data was produced although tests ran")
