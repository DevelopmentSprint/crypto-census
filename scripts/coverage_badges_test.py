#!/usr/bin/env python3
"""Coverage export contracts; fixtures are synthetic, not publication values."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import coverage_badges as coverage


class CoverageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for index, (module, path) in enumerate(coverage.REPORTS.items()):
            self.write(module, path, f"package/Class{index}", 1, index + 1)

    def write(self, module, path, name, covered, missed):
        counters = ''.join(f'<counter type="{m}" covered="{covered}" missed="{missed}"/>' for m in coverage.METRICS)
        report = f'<?xml version="1.0"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd"><report name="test"><package name="package"><class name="{name}"/>{counters}</package>{counters}</report>'
        target = self.root / module / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(report)
        return target

    def first(self):
        module, path = next(iter(coverage.REPORTS.items()))
        return self.root / module / path

    def test_weighted_aggregate_and_one_runtime_report(self):
        modules, aggregate = coverage.measure(self.root)
        self.assertEqual(aggregate['line'], {'covered': 5, 'missed': 15, 'percent': 25.0})
        self.assertNotEqual(aggregate['line']['percent'], sum(m['line']['percent'] for m in modules) / 5)
        self.assertIn('jacoco-runtime', modules[1]['report'])
        self.assertEqual(len(modules), 5)

    def test_missing_report_fails(self):
        self.first().unlink()
        with self.assertRaises(FileNotFoundError):
            coverage.measure(self.root)

    def test_negative_non_integer_or_zero_denominator_fails(self):
        for covered, missed in ((-1, 2), ('NaN', 2), (1.2, 2), (0, 0)):
            with self.subTest(covered=covered, missed=missed):
                module, path = next(iter(coverage.REPORTS.items()))
                self.write(module, path, 'Unique', covered, missed)
                with self.assertRaises(ValueError):
                    coverage.measure(self.root)

    def test_duplicate_classes_across_modules_fail(self):
        module, path = list(coverage.REPORTS.items())[1]
        self.write(module, path, 'package/Class0', 1, 1)
        with self.assertRaisesRegex(ValueError, 'Duplicate class'):
            coverage.measure(self.root)

    def test_duplicate_classes_within_module_fail(self):
        path = self.first()
        path.write_text(path.read_text().replace('<class name="package/Class0"/>', '<class name="package/Class0"/>' * 2))
        with self.assertRaisesRegex(ValueError, 'Duplicate class'):
            coverage.measure(self.root)

    def test_counter_mismatch_or_duplicates_fail(self):
        path = self.first()
        original = path.read_text()
        for changed in (original.replace('covered="1"', 'covered="9"', 1), original.replace('</report>', '<counter type="LINE" covered="1" missed="1"/></report>')):
            path.write_text(changed)
            with self.assertRaises(ValueError):
                coverage.measure(self.root)

    def test_entities_and_internal_subsets_rejected(self):
        for xml in ('<!DOCTYPE report [<!ENTITY x "expanded">]><report/>', '<!DOCTYPE report [ ]><report/>'):
            self.first().write_text(xml)
            with self.assertRaisesRegex(ValueError, 'forbidden'):
                coverage.measure(self.root)

    def test_utf16_internal_entities_rejected(self):
        self.first().write_bytes('<!DOCTYPE report [<!ENTITY x "expanded">]><report/>'.encode('utf-16'))
        with self.assertRaises(ValueError):
            coverage.measure(self.root)

    def test_oversized_reports_rejected(self):
        with patch.object(coverage, 'MAX_REPORT_BYTES', 10):
            with self.assertRaisesRegex(ValueError, 'too large'):
                coverage.measure(self.root)

    def test_provenance_and_valid_svg(self):
        output = self.root / 'output'
        with contextlib.redirect_stdout(io.StringIO()):
            coverage.main(['--root', str(self.root), '--output', str(output), '--sha', 'a' * 40,
                           '--run-url', 'https://github.com/DevelopmentSprint/crypto-census/actions/runs/123'])
        result = json.loads((output / 'coverage.json').read_text())
        self.assertEqual(result['sourceSha'], 'a' * 40)
        self.assertEqual(result['aggregate']['branch']['percent'], 25.0)
        for name in ('coverage.svg', 'coverage-branches.svg'):
            self.assertEqual(ET.parse(output / name).getroot().tag, '{http://www.w3.org/2000/svg}svg')
            self.assertIn('25.0%', (output / name).read_text())

    def test_missing_input_does_not_create_publication_output(self):
        self.first().unlink()
        output = self.root / 'output'
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            coverage.main(['--root', str(self.root), '--output', str(output), '--sha', 'a' * 40,
                           '--run-url', 'https://github.com/DevelopmentSprint/crypto-census/actions/runs/123'])
        self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
