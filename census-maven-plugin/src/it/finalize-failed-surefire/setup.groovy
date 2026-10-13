import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

def storeFile = new File(basedir, 'server.p12')
def executable = System.getProperty('os.name').startsWith('Windows') ? 'keytool.exe' : 'keytool'
def keytool = new File(System.getProperty('java.home'), 'bin/' + executable)
def password = 'ephemeral-test-only'
def command = [keytool.path, '-genkeypair', '-alias', 'localhost', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost', '-ext', 'bc=ca:true', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-storetype', 'PKCS12', '-keystore', storeFile.path, '-storepass', password, '-noprompt']
def process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(new File(basedir, 'keytool.log')).start()
assert process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
assert process.exitValue() == 0
def store = KeyStore.getInstance('PKCS12')
storeFile.withInputStream { store.load(it, password.toCharArray()) }
def keys = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
keys.init(store, password.toCharArray())
def ssl = SSLContext.getInstance('TLS')
ssl.init(keys.keyManagers, null, null)
new File(basedir, 'ca.pem').text = '-----BEGIN CERTIFICATE-----\n' + Base64.mimeEncoder.encodeToString(store.getCertificate('localhost').encoded) + '\n-----END CERTIFICATE-----\n'
def server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress([127,0,0,1] as byte[]), 0), 0)
server.httpsConfigurator = new HttpsConfigurator(ssl)
server.createContext('/ingest') { exchange ->
  new File(basedir, 'uploaded.json').bytes = exchange.requestBody.bytes
  exchange.sendResponseHeaders(204, -1)
  exchange.close()
}
server.start()
context.put('deliveryServer', server)
def pom = new File(basedir, 'pom.xml')
pom.text = pom.text.replace('__PORT__', server.address.port.toString())

return true
