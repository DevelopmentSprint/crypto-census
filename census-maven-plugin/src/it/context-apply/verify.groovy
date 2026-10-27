import groovy.json.JsonSlurper

def context = new File(basedir, 'private/context.json')
assert context.isFile()
assert new JsonSlurper().parse(context).flows[0].label == 'Records archive'
def log = new File(basedir, 'build.log').text
assert log.contains('Context changes applied')
assert log.contains('Context already contains these changes')
assert !new File(basedir, 'target/context.json').exists()
