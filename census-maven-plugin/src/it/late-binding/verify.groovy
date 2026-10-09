def cbom = new File(basedir, "target/cryptocensus/cbom.json")
assert cbom.isFile() : "agent did not attach through @{censusArgLine}"
assert cbom.text.contains("SHA256withRSA")
