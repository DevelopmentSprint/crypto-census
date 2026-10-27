import groovy.json.JsonSlurper
import java.security.MessageDigest

def root = new File(basedir, "target/cryptocensus")
def runs = root.listFiles().findAll { it.isDirectory() && it.name != "assessment" }
assert runs.size() == 1
def run = runs[0]
def cboms = run.listFiles().findAll { it.name.startsWith("cbom-") && it.name.endsWith(".json") && !it.name.endsWith(".evidence.json") }
assert cboms.size() == 2
def catalog = new JsonSlurper().parse(new File(run, "sources.json"))
assert catalog.entries.any { it.artifactId == 'it.cryptocensus:context-enrichment' && it.origin == 'MAIN' }
assert catalog.entries.any { it.artifactId == 'it.cryptocensus:context-enrichment' && it.origin == 'TEST' }
assert catalog.entries.any { it.artifactId == 'org.junit.jupiter:junit-jupiter-api' && it.origin == 'LIBRARY' }
cboms.each { cbom ->
  def sidecar = new File(run, cbom.name.replace('.json', '.evidence.json'))
  assert sidecar.isFile()
  def evidence = new JsonSlurper().parse(sidecar)
  assert evidence.cbomHash == MessageDigest.getInstance('SHA-256').digest(cbom.bytes).encodeHex().toString()
  assert evidence.observations
  assert evidence.observations.every { it.context.association == 'NO_SCOPE' }
  assert evidence.identities.any { it.applicationId == 'it.cryptocensus:context-enrichment' }
}
assert new File(root, 'cbom.json').isFile()
assert new File(basedir, 'build.log').text.contains('merged from 2 JVMs')

def report = new JsonSlurper().parse(new File(root, 'assessment/assessment.json'))
assert report.runs.size() == 2
assert report.runs.every { it.evidenceAccepted }

def html = new File(root, 'assessment/assessment.html').text
def embedded = html =~ /(?s)<script id="report-data" type="application\/json">(.*?)<\/script>/
assert embedded.find()
assert new JsonSlurper().parseText(embedded.group(1)) == report
