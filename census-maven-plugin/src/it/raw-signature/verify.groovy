def bom = new groovy.json.JsonSlurper().parse(new File(basedir, "target/cryptocensus/cbom.json"))
def props = { component -> component.properties.collectEntries { [(it.name): it.value] } }
assert props(bom.metadata)["cryptocensus:policy"] == "cryptocensus-default/6"
def log = new File(basedir, "build.log").text
def raw = bom.components.collect(props).findAll { it["cryptocensus:algorithm"] == "NONEwithECDSA" }
assert raw.size() == 2 : "both raw signing and verification must be recorded"
assert raw.every { it["cryptocensus:risk"] == "unknown" }
assert log.contains("hash computed outside JCA, not observed")
assert log =~ /asset\(s\) the policy cannot judge:\r?\n(?:\[ERROR\])?\s*NONEwithECDSA-256 \((?:sign|verify)\) \[unknown\]/
assert log.contains("BUILD FAILURE")
assert !log.contains("There are test failures")
