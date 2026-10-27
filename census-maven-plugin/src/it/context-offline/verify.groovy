import groovy.json.JsonSlurper

def first = new JsonSlurper().parse(new File(basedir, 'target/first/assessment.json'))
def second = new JsonSlurper().parse(new File(basedir, 'target/second/assessment.json'))
assert first.contextHash != second.contextHash
assert first.runs*.cbomHash == second.runs*.cbomHash
assert first.baseContext.flows.isEmpty()
assert second.baseContext.flows[0].label == 'Records archive'
assert new File(basedir, 'target/test-ran').text == 'once'
assert new File(basedir, 'build.log').text.contains('no application tests were run')

def manual = new JsonSlurper().parse(new File(basedir, 'target/manual/assessment.json'))
assert manual.runs.size() == 1
assert manual.runs*.cbomHash == first.runs*.cbomHash

['first','second','manual'].each { name ->
  def html = new File(basedir, 'target/' + name + '/assessment.html').text
  def embedded = html =~ /(?s)<script id="report-data" type="application\/json">(.*?)<\/script>/
  assert embedded.find()
  assert new JsonSlurper().parseText(embedded.group(1)) == new JsonSlurper().parse(new File(basedir, 'target/' + name + '/assessment.json'))
}

return true
