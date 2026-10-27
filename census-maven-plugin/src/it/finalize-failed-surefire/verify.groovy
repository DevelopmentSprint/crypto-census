try {
  def manifest = new File(basedir, "target/cryptocensus/run-11111111-1111-4111-8111-111111111111-96e0d921b36fe08a6f92d20312e0f8f0de83d2ed0a71ef7bf19e9eace4caff1b/revisions/1/manifest.json")
  def seal = new groovy.json.JsonSlurper().parse(manifest)
  assert seal.testOutcome == 'failed'
  assert seal.policyOutcome == 'failed'
  assert seal.descriptor.runId == '11111111-1111-4111-8111-111111111111'
  def evidence = new File(manifest.parentFile, seal.artifact)
  assert evidence.bytes == new File(basedir, 'uploaded.json').bytes
  assert evidence.text.contains('MD5')
  def log = new File(basedir, 'build.log').text
  assert log.contains('intentional producer failure')
  assert log.contains('cryptographic asset(s) at or above')
  assert log.contains('Current-run evidence is missing or ambiguous')
  assert new File(basedir, 'target/cryptocensus/delivery').listFiles().size() == 2
} finally { context.get('deliveryServer')?.stop(0) }
