def runs = new File(basedir, 'target/shared').listFiles().findAll { it.name.startsWith('run-') }
assert runs.size() == 2
def seals = runs.collect { new groovy.json.JsonSlurper().parse(new File(it, 'revisions/1/manifest.json')) }
assert seals.collect { it.descriptor.module }.toSet() == ['it.cryptocensus:alpha', 'it.cryptocensus:beta'].toSet()
assert seals.collect { it.descriptor.moduleRunId }.toSet().size() == 2
assert seals.find { it.descriptor.module.endsWith(':alpha') }.policyOutcome == 'passed'
assert seals.find { it.descriptor.module.endsWith(':beta') }.policyOutcome == 'failed'
