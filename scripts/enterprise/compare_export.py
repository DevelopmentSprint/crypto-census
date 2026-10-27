#!/usr/bin/env python3
"""Offline structural comparison of an original CBOM and an operator-captured native export."""
import argparse
import json
import os
from pathlib import Path

from live_delivery import compare_export, sha256, write_private


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', required=True, type=Path)
    parser.add_argument('--native-export', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    result = compare_export(json.loads(args.original.read_text()), json.loads(args.native_export.read_text()))
    result.update({'schema': 'census.native-export-comparison/1',
                   'originalSha256': sha256(args.original), 'nativeExportSha256': sha256(args.native_export)})
    write_private(args.output, result)
    print('Comparison written. Review DIFFERENT and NOT_EXERCISED fields; this does not certify native semantic fidelity.')


if __name__ == '__main__':
    main()
