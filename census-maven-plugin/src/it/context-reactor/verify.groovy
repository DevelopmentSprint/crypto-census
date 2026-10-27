import groovy.json.JsonSlurper
boolean resolvedCopy = false
['library','consumer'].each { module ->
  def root = new File(basedir, module + '/target/cryptocensus')
  def runs = root.listFiles().findAll { it.isDirectory() }
  assert runs.size() == (module == 'consumer' ? 2 : 1)
  runs.each { run ->
    def files = run.listFiles().findAll { it.name.endsWith('.evidence.json') }
    assert files.size() == (module == 'consumer' ? 2 : 1)
    files.each { evidenceFile ->
      def evidence = new JsonSlurper().parse(evidenceFile)
      assert evidence.flows.any { it.definition.subject.applicationId == 'billing-archive' && it.definition.subject.flowId == 'archive' }
      assert evidence.identities.any { it.applicationId == 'billing-archive' && it.sourceArtifact == 'it.context:library' }
      assert evidence.observations.any { it.context.association == 'SYNCHRONOUS_SCOPE' }
    }
    def catalog = new JsonSlurper().parse(new File(run, 'sources.json'))
    assert catalog.entries.any { it.artifactId == 'it.context:library' && it.locationUri.endsWith('/library-1.jar') && it.origin == 'MAIN' }
    if (catalog.entries.any { it.locationUri.endsWith('/it/context/library/1/library-1.jar') && it.applicationId == 'billing-archive' && it.origin == 'MAIN' }) resolvedCopy = true
  }
}
assert resolvedCopy : 'resolved packaged copy did not retain declaring reactor identity'
