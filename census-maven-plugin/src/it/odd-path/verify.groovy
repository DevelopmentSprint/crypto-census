def cbom = new File(basedir, "target/merged/cbom.json")
assert cbom.isFile() : "merged CBOM missing at " + cbom
def text = cbom.text
assert text.contains("SHA256withRSA")
assert text.contains("AES")
def odd = new File(basedir, "target/odd, dir=x 'q'")
assert odd.isDirectory() : "agent did not write into the requested directory"
assert !new File("x 'q'").exists()
