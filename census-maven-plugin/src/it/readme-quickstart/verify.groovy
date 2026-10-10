def bom = new groovy.json.JsonSlurper().parse(new File(basedir, "target/cryptocensus/cbom.json"))
def props = { component -> component.properties.collectEntries { [(it.name): it.value] } }
assert props(bom.metadata)["cryptocensus:policy"] == "cryptocensus-default/6"
def log = new File(basedir, "build.log").text
assert bom.components.collect(props).any {
    it["cryptocensus:algorithm"] == "SHA256withRSA" && it["cryptocensus:risk"] == "quantum-vulnerable"
}
assert log.contains("at or above 'quantum-vulnerable'")
assert !log.contains("There are test failures")
