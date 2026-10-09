def cbom = new File(basedir, "target/cryptocensus/cbom.json")
assert cbom.isFile() : "merged CBOM missing at " + cbom
def text = cbom.text
assert text.contains("SHA256withRSA")
assert text.contains("AES")
def log = new File(basedir, "build.log").text
assert !log.contains("hunter2") : "inherited argLine was logged"
assert log.contains("merged from 1 JVM")
