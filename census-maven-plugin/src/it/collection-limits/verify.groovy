def reports = []
new File(basedir, "target/cryptocensus").eachFileRecurse { file ->
    if (file.name.startsWith("cbom-") && file.name.endsWith(".json")) reports << file
}
assert reports.size() == 1 : "expected one forked JVM report"
def raw = new groovy.json.JsonSlurper().parse(reports[0])
def props = raw.metadata.properties.collectEntries { [(it.name): it.value] }
assert props['cryptocensus:maxAssets'] == '7'
assert props['cryptocensus:maxSites'] == '1'
assert props['cryptocensus:dropped'] == '0'
assert props['cryptocensus:overflowedSites'].toLong() >= 1
def merged = new groovy.json.JsonSlurper().parse(new File(basedir, "target/cryptocensus/cbom.json"))
assert merged.components.any { c -> c.evidence.occurrences.any { it.location == '<overflow>' } }
assert new File(basedir, "build.log").text.contains("lost their call-site attribution")
