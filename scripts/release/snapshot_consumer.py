#!/usr/bin/env python3
"""Exercise public candidate artifacts from external applications and empty Maven caches."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from xml.sax.saxutils import escape

URL = 'https://central.sonatype.com/repository/maven-snapshots/'
WORKLOAD = '''import java.security.*;
public class CryptoWorkload {
  public static void main(String[] args) throws Exception {
    MessageDigest.getInstance("SHA-256").digest(new byte[]{1, 2, 3});
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(pair.getPrivate());
    signature.update(new byte[]{1, 2, 3});
    signature.sign();
  }
}
'''


def settings(url):
    repository = f'''<id>census-candidate</id><url>{escape(url)}</url>
      <releases><enabled>false</enabled></releases>
      <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>'''
    return f'''<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><profiles>
      <profile><id>candidate</id><repositories><repository>{repository}</repository></repositories>
      <pluginRepositories><pluginRepository>{repository}</pluginRepository></pluginRepositories>
      </profile></profiles><activeProfiles><activeProfile>candidate</activeProfile></activeProfiles></settings>'''


def inspect_cbom(path):
    report = json.loads(path.read_text())
    components = [c for c in report.get('components', []) if c.get('type') == 'cryptographic-asset']
    if not components or 'SHA-256' not in json.dumps(components) or 'RSA' not in json.dumps(components):
        raise ValueError('Expected collected SHA-256 and RSA cryptographic assets')


def run(args):
    maven = str(Path(args.maven).resolve()) if '/' in args.maven else args.maven
    with tempfile.TemporaryDirectory(prefix='census-external-consumer-') as temporary:
        root = Path(temporary)
        config = root / 'settings.xml'
        config.write_text(settings(args.repository))
        global_config = root / 'global-settings.xml'
        global_config.write_text('<settings/>')
        # Never inherit publishing settings, Maven options or command-line credentials.
        env = {k: v for k, v in os.environ.items() if not k.startswith(('CENSUS_CENTRAL_', 'MAVEN_', 'MVND_', 'GPG_'))}
        base = [maven, '-B', '-ntp', '-U', '-s', str(config), '-gs', str(global_config)]

        def invoke(command, cwd, *, fail_policy=False):
            result = subprocess.run(command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, check=False)
            if fail_policy:
                okay = result.returncode != 0 and "cryptographic asset(s) at or above 'quantum-vulnerable'" in result.stdout
            else:
                okay = result.returncode == 0
            if not okay:
                print(result.stdout)
                raise ValueError('External consumer command failed its expected outcome')

        app = root / 'plugin-app'
        source = app / 'src/test/java'
        source.mkdir(parents=True)
        (source / 'CryptoWorkload.java').write_text(WORKLOAD)
        (source / 'CryptoTest.java').write_text('''import org.junit.jupiter.api.Test;
public class CryptoTest { @Test void usesCrypto() throws Exception { CryptoWorkload.main(new String[0]); } }''')
        readme = Path(__file__).resolve().parents[2] / 'README.md'
        snippet = readme.read_text().split('<!-- quickstart-plugin:start -->')[1].split('<!-- quickstart-plugin:end -->')[0]
        plugin = snippet.split('```xml')[1].split('```')[0].strip()
        plugin, count = re.subn(r'<version>[^<]+</version>', f'<version>{args.version}</version>', plugin)
        if count != 1:
            raise ValueError('Unexpected quickstart plugin version')
        (app / 'pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>example.external</groupId><artifactId>snapshot-smoke</artifactId><version>1</version>
<properties><maven.compiler.release>17</maven.compiler.release></properties>
<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
{plugin}</plugins></build></project>''')
        plugin_command = base + [f'-Dmaven.repo.local={root / "plugin-cache"}']
        invoke(plugin_command + ['clean', 'verify'], app)
        report = app / 'target/cryptocensus/cbom.json'
        inspect_cbom(report)
        invoke(plugin_command + ['clean', 'verify', '-Dcensus.failOn=quantum-vulnerable'], app, fail_policy=True)
        inspect_cbom(report)

        standalone = root / 'standalone'
        standalone.mkdir()
        (standalone / 'CryptoWorkload.java').write_text(WORKLOAD)
        invoke(base + [f'-Dmaven.repo.local={root / "agent-cache"}',
                      'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy',
                      f'-Dartifact=com.developmentsprint:census-agent:{args.version}',
                      f'-DoutputDirectory={standalone}', '-Dmdep.stripVersion=true',
                      f'-DremoteRepositories=census-candidate::default::{args.repository}'], standalone)
        invoke(['javac', 'CryptoWorkload.java'], standalone)
        invoke(['java', '-javaagent:census-agent.jar=output=cbom.json,app=snapshot-smoke',
                'CryptoWorkload'], standalone)
        inspect_cbom(standalone / 'cbom.json')
        # Resolve the full published coordinate/classifier set independently of the reactor.
        artifacts = [('census-parent', 'pom', None)]
        for module in ('census-annotations', 'census-core', 'census-assessment', 'census-agent',
                       'census-delivery', 'census-cli', 'census-maven-plugin'):
            artifacts += [(module, kind, classifier) for kind, classifier in
                          (('pom', None), ('jar', None), ('jar', 'sources'), ('jar', 'javadoc'))]
        artifacts.append(('census-delivery', 'jar', 'tests'))
        for module, kind, classifier in artifacts:
            coordinate = f'com.developmentsprint:{module}:{args.version}:{kind}'
            if classifier:
                coordinate += ':' + classifier
            invoke(base + [f'-Dmaven.repo.local={root / "agent-cache"}',
                          'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy',
                          '-Dartifact=' + coordinate, f'-DoutputDirectory={standalone}',
                          '-Dmdep.stripVersion=true'], standalone)
        invoke(['java', '-jar', 'census-cli.jar', '--help'], standalone)
        print(f'External {args.version} consumer passed: quickstart, strict policy failure, standalone agent, CLI, all 22 artifacts; two fresh caches')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', default=URL)
    parser.add_argument('--maven', default='mvn')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT', args.version):
        parser.error('Expected x.y.z-SNAPSHOT')
    run(args)


if __name__ == '__main__':
    main()
