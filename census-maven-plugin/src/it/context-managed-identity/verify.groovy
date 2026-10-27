import groovy.json.JsonSlurper
// First invocation instruments the managed-only library via default-cli. The second
// invokes only the consumer's lifecycle binding; the library model has no active plugin.
['library', 'consumer'].each { module ->
  def root = new File(basedir, module + '/target/cryptocensus')
  def runs = root.listFiles().findAll { it.isDirectory() }
  assert runs.size() == 1
  def run = runs[0]
  def catalog = new JsonSlurper().parse(new File(run, 'sources.json'))
  def declaring = catalog.entries.findAll { it.artifactId == 'it.managed:library' }
  assert declaring && declaring.every { it.applicationId == 'managed-archive' }
  def evidenceFiles = run.listFiles().findAll { it.name.endsWith('.evidence.json') }
  assert evidenceFiles.size() == 1
  def evidence = new JsonSlurper().parse(evidenceFiles[0])
  assert evidence.flows.size() == 1
  assert evidence.flows[0].definition.subject == [applicationId:'managed-archive', flowId:'archive']
  assert evidence.observations.findAll { it.context.association == 'SYNCHRONOUS_SCOPE' }.sum { it.count } == 1
}
return true
