def cbom = new File(basedir, "target/cryptocensus/cbom.json")
assert cbom.isFile() : "merged CBOM missing at " + cbom
def text = cbom.text
assert text.contains("SHA256withRSA")
assert text.contains("AES")
def log = new File(basedir, "build.log").text
assert !log.contains("hunter2") : "inherited argLine was logged"
assert log.contains("merged from 1 JVM")
// fixed instrumentation targets are carried into the merged CBOM; nothing used TLS here
assert text.contains('"cryptocensus:targets"')
assert text.contains("javax.crypto.Cipher=instrumented")
assert text.contains("sun.security.ssl.SSLSessionImpl=not-loaded")
assert log.contains("Not exercised by any test JVM (never loaded):")
assert !log.contains("Instrumentation gap")
