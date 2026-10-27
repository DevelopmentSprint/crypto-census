import groovy.json.JsonSlurper

def runs = new File(basedir, 'target/cryptocensus').listFiles().findAll { it.isDirectory() }
assert runs.size() == 2
def documents = runs.collect { run -> new JsonSlurper().parse(run.listFiles().find { it.name.endsWith('.evidence.json') }) }
assert documents.any { it.flows.isEmpty() && it.observations.any { observation -> observation.context.association == 'SCOPE_LOST' } }
assert documents.any { it.flows.any { flow -> flow.definition.subject.applicationId == 'billing' } && it.identities.any { identity -> identity.sourceArtifact == 'explicit:archive-container' } }
