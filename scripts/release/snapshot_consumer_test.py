import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import snapshot_consumer


class ConsumerChecks(unittest.TestCase):
    def test_settings_enable_snapshot_dependencies_and_plugins_without_credentials(self):
        xml = snapshot_consumer.settings('file:///tmp/candidate&escaped')
        root = ET.fromstring(xml)
        ns = {'m': 'http://maven.apache.org/SETTINGS/1.0.0'}
        self.assertIsNone(root.find('m:servers', ns))
        for path in ('.//m:repository', './/m:pluginRepository'):
            repository = root.find(path, ns)
            self.assertEqual(repository.findtext('m:url', namespaces=ns), 'file:///tmp/candidate&escaped')
            self.assertEqual(repository.findtext('m:snapshots/m:enabled', namespaces=ns), 'true')
            self.assertEqual(repository.findtext('m:releases/m:enabled', namespaces=ns), 'false')

    def test_cbom_requires_both_actual_crypto_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'cbom.json'
            for components in ([], [{'type': 'library', 'name': 'SHA-256 RSA'}],
                               [{'type': 'cryptographic-asset', 'name': 'SHA-256'}]):
                path.write_text(json.dumps({'components': components}))
                with self.assertRaises(ValueError):
                    snapshot_consumer.inspect_cbom(path)
            path.write_text(json.dumps({'components': [
                {'type': 'cryptographic-asset', 'name': 'SHA-256'},
                {'type': 'cryptographic-asset', 'name': 'RSA'}]}))
            snapshot_consumer.inspect_cbom(path)


if __name__ == '__main__':
    unittest.main()
