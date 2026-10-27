import groovy.json.JsonOutput
new File(basedir, 'explicit-sources.json').text = JsonOutput.toJson([
  schemaVersion: 1,
  entries: [[locationUri: new File(basedir, 'target/shaded-1.jar').toURI().toString(), artifactId: 'explicit:archive-container', applicationId: 'billing', origin: 'MAIN', deploymentId: 'reviewed-build', loaderName: '']]
])

return true
