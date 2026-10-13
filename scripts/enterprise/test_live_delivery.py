import copy
import unittest
import json
import tempfile
import contextlib
import io
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

from live_delivery import compare_export, inspect_receipt, main, sha256


class LiveEvidenceTest(unittest.TestCase):
    def receipt(self, adapter='artifactory'):
        return {'schema': 'census.delivery-receipt/2', 'adapter': adapter,
                'artifactSha256': 'artifact', 'planSha256': 'plan', 'gatePassed': True,
                'upload': 'ACCEPTED', 'ingestion': 'NOT_APPLICABLE' if adapter == 'artifactory' else 'COMPLETED',
                'reasonCode': 'ARTIFACTORY_STORED_EXACT_BYTES',
                'destinationReadback': {'observation': 'RETRIEVED_EXACT_BYTES', 'artifactSha256': 'artifact'},
                'originalRetention': {'remote': 'RETRIEVED_EXACT_BYTES', 'artifactSha256': 'artifact'}}

    def test_upload_acknowledgment_without_readback_is_not_storage_success(self):
        receipt = self.receipt()
        receipt['destinationReadback'] = {'observation': 'UNVERIFIED'}
        with self.assertRaises(ValueError):
            inspect_receipt(receipt, 'artifactory', 'artifact', 'plan')

    def test_receipt_cannot_substitute_other_artifact_or_plan(self):
        for key in ('artifactSha256', 'planSha256'):
            receipt = self.receipt()
            receipt[key] = 'other'
            with self.assertRaises(ValueError):
                inspect_receipt(receipt, 'artifactory', 'artifact', 'plan')

    def test_ingestion_adapters_require_completed_import_and_independent_original(self):
        for adapter in ('anchore-enterprise', 'sonatype-sbom-manager'):
            for key, value in [('ingestion', 'PENDING'), ('originalRetention', {})]:
                receipt = self.receipt(adapter)
                receipt[key] = value
                with self.assertRaises(ValueError):
                    inspect_receipt(receipt, adapter, 'artifact', 'plan')

    def test_anchore_does_not_invent_native_byte_retention(self):
        receipt = self.receipt('anchore-enterprise')
        receipt['destinationReadback'] = {'observation': 'UNVERIFIED'}
        inspect_receipt(receipt, 'anchore-enterprise', 'artifact', 'plan')

    def test_absent_fields_are_unexercised_not_preserved(self):
        comparison = compare_export({'components': []}, {'components': []})
        self.assertEqual(set(comparison['fields'].values()), {'NOT_EXERCISED'})

    def test_lost_crypto_fields_are_reported_even_if_identity_matches(self):
        original = {'components': [{'bom-ref': 'crypto-1', 'type': 'cryptographic-asset',
                                    'cryptoProperties': {'assetType': 'algorithm'}}]}
        export = copy.deepcopy(original)
        del export['components'][0]['cryptoProperties']
        result = compare_export(original, export)['fields']
        self.assertEqual(result['componentIdentityAndType'], 'MATCH')
        self.assertEqual(result['cryptoProperties'], 'DIFFERENT')
        self.assertEqual(result['signature'], 'NOT_EXERCISED')

    def test_component_order_does_not_hide_or_invent_field_loss(self):
        original = {'components': [{'bom-ref': 'a', 'type': 'cryptographic-asset'},
                                   {'bom-ref': 'b', 'type': 'cryptographic-asset'}]}
        export = {'components': list(reversed(original['components']))}
        self.assertEqual(compare_export(original, export)['fields']['componentIdentityAndType'], 'MATCH')

    def test_runner_binds_execution_to_embedded_plan_digest_and_stops_on_failure(self):
        for fail_preflight in (False, True):
            with self.subTest(fail_preflight=fail_preflight), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                artifact = root / "cbom.json"
                artifact.write_text('{}')
                jar = root / "cli.jar"
                jar.write_bytes(b'unit-test-placeholder-not-a-vendor')
                profile = root / "profile.json"
                profile.write_text(json.dumps({'adapter': 'artifactory', 'preparation': {'mode': 'IDENTITY'},
                    'inputs': [{'path': 'cbom.json', 'sha256': sha256(artifact)}],
                    'outputs': {'plan': 'plan.json', 'receipt': 'receipt.json'}}))
                embedded_digest = 'a' * 64
                commands = []
                def command_runner(command, **kwargs):
                    commands.append(command)
                    action = command[command.index('--action') + 1]
                    if action == 'plan':
                        (root / 'plan.json').write_text(json.dumps({'planSha256': embedded_digest, 'plan': {'schema': 'test'}}))
                        self.assertNotEqual(sha256(root / 'plan.json'), embedded_digest)
                    else:
                        self.assertEqual(command[command.index('--plan-sha256') + 1], embedded_digest)
                    if action == 'publish':
                        receipt = self.receipt()
                        receipt.update({'artifactSha256': sha256(artifact), 'planSha256': embedded_digest})
                        receipt['destinationReadback']['artifactSha256'] = sha256(artifact)
                        (root / 'receipt.json').write_text(json.dumps(receipt))
                    return SimpleNamespace(returncode=4 if action == 'preflight' and fail_preflight else 0)
                argv = ['live_delivery.py', '--profile', str(profile), '--entry-point', 'cli',
                        '--cli-jar', str(jar), '--product-version', 'unit-test', '--output', str(root / 'output'), '--allow-publish']
                with patch('sys.argv', argv), patch('live_delivery.subprocess.run', command_runner), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main(), 1 if fail_preflight else 0)
                self.assertEqual(len(commands), 2 if fail_preflight else 3)
                evidence = json.loads((root / 'output/evidence.json').read_text())
                self.assertEqual(evidence['planSha256'], embedded_digest)
                self.assertEqual(evidence['nativeSemantics'], 'UNVERIFIED')
                self.assertEqual(evidence['result'], 'FAILED_OR_UNCONFIRMED' if fail_preflight else 'DELIVERY_CONTRACT_PASSED')

    def test_nested_component_and_identity_field_loss_cannot_match_complete_components(self):
        original = {'components': [{'bom-ref': 'parent', 'type': 'application', 'components': [
            {'bom-ref': 'child', 'type': 'cryptographic-asset', 'name': 'SHA-256', 'version': '1',
             'purl': 'pkg:generic/example@1', 'hashes': [{'alg': 'SHA-256', 'content': 'hash'}],
             'cryptoProperties': {'assetType': 'algorithm'}}]}]}
        for key in ('name', 'version', 'purl', 'hashes', 'cryptoProperties'):
            exported = copy.deepcopy(original)
            del exported['components'][0]['components'][0][key]
            self.assertEqual(compare_export(original, exported)['fields']['completeComponents'], 'DIFFERENT')
        exported = copy.deepcopy(original)
        del exported['components'][0]['components']
        result = compare_export(original, exported)['fields']
        self.assertEqual(result['completeComponents'], 'DIFFERENT')
        self.assertEqual(result['componentHierarchy'], 'DIFFERENT')

    def test_ambiguous_native_identity_rejected(self):
        for components in [[{'type': 'library'}], [{'bom-ref': 'a'}, {'bom-ref': 'a'}]]:
            with self.assertRaises(ValueError):
                compare_export({'components': []}, {'components': components})


if __name__ == '__main__':
    unittest.main()
