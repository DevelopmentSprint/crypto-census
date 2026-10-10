def bom = new groovy.json.JsonSlurper().parse(new File(basedir, "target/cryptocensus/cbom.json"))
def props = { component -> component.properties.collectEntries { [(it.name): it.value] } }
assert props(bom.metadata)["cryptocensus:policy"] == "cryptocensus-default/6"
def log = new File(basedir, "build.log").text
def signature = bom.components.collect(props).find {
    it["cryptocensus:algorithm"] == "MD5withRSA/ISO9796-2" && it["cryptocensus:operation"] == "SIGN"
}
assert signature != null : "real Bouncy Castle signing must be recorded"
assert signature["cryptocensus:keySize"] == "2048"
assert signature["cryptocensus:risk"] == "broken"
assert log.contains("at or above 'broken'")
assert log.contains("MD5withRSA/ISO9796-2")
assert !log.contains("There are test failures")
