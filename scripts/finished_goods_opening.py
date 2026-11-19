"""Prepare a read-only, source-linked review of 1C finished-goods openings.

This tool never posts stock. Live MM balances, activity, master mappings and
valuations must be reconciled before a separate controlled activation.
"""
import argparse
from collections import Counter
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3


ACCOUNTS = frozenset(('2810', '2811', '2812', '2813'))


def bind_materials(positions, bindings):
    catalog = {'00000036': '_reference54:', '00000037': '_reference55:', '000016f4': '_reference5876:'}
    for position in positions:
        prefix = catalog.get(position['materialReferenceType'])
        position['materialId'] = bindings.get(prefix + position['materialSourceId']) if prefix else None


def exact_decimal(value):
    if not isinstance(value, str):
        raise ValueError('Source decimal must be a string')
    try:
        result = Decimal(value)
    except InvalidOperation as error:
        raise ValueError('Invalid source decimal') from error
    if not result.is_finite():
        raise ValueError('Source decimal must be finite')
    return result


def has_extra_dimension(dimensions):
    for key, value in (dimensions or {}).items():
        if (key == 'currencySourceId' or re.match(r'dimension([3-9]|[1-9]\d+)_', key)) and value not in (None, '', '00', '00000000', '0' * 32):
            return True
    return False


def prepare_positions(rows, *, source_organization=None, snapshot_date=None):
    groups, source_keys = {}, set()
    for row in rows:
        if row.get('accountCode') not in ACCOUNTS or row.get('stockCategory') != 'FINISHED_PRODUCTS':
            continue
        if ((source_organization is not None and row.get('organizationSourceId') != source_organization)
                or (snapshot_date is not None and row.get('sourceDate') != snapshot_date)):
            raise ValueError('Source record organization or snapshot date does not match manifest')
        source_key = row.get('sourceKey')
        if not source_key or source_key in source_keys:
            raise ValueError('Missing or duplicate source key')
        source_keys.add(source_key)
        identity = tuple(row.get(k) for k in ('warehouseSourceId', 'materialReferenceType', 'materialSourceId', 'uom'))
        if any(not value or value == '0' * 32 for value in identity):
            raise ValueError('Missing source warehouse, catalog, material or unit identity')
        quantity, amount = exact_decimal(row.get('quantity')), exact_decimal(row.get('amount'))
        if identity not in groups:
            groups[identity] = {'warehouseSourceId': identity[0], 'materialReferenceType': identity[1],
                'materialSourceId': identity[2], 'uom': identity[3], 'warehouseName': row.get('warehouseName'),
                'materialCode': row.get('materialCode'), 'materialName': row.get('materialName'),
                'uomName': row.get('uomNameRu'), 'quantity': Decimal(0), 'amount': Decimal(0),
                'sources': [], 'extraDimensions': False, '_warehouseIds': set()}
        position = groups[identity]
        if row.get('warehouseId'):
            position['_warehouseIds'].add(row['warehouseId'])
        position['quantity'] += quantity
        position['amount'] += amount
        position['extraDimensions'] |= has_extra_dimension(row.get('additionalDimensions'))
        position['sources'].append({'sourceKey': source_key, 'accountCode': row['accountCode'],
                                    'quantity': row['quantity'], 'amount': row['amount']})

    result = []
    for key in sorted(groups):
        position = groups[key]
        quantity, amount = position['quantity'], position['amount']
        position['sources'].sort(key=lambda item: item['sourceKey'])
        position['sourceKeys'] = [item['sourceKey'] for item in position['sources']]
        warehouse_ids = position.pop('_warehouseIds')
        position['warehouseId'] = next(iter(warehouse_ids)) if len(warehouse_ids) == 1 else None
        if len(warehouse_ids) > 1:
            position['status'] = 'MAPPING_REVIEW'
        elif position['extraDimensions']:
            position['status'] = 'DIMENSIONS_REVIEW'
        elif quantity < 0 or amount < 0:
            position['status'] = 'NEGATIVE_REVIEW'
        elif quantity == 0 and amount != 0:
            position['status'] = 'VALUE_ONLY_REVIEW'
        elif quantity == 0:
            position['status'] = 'ZERO'
        else:
            position['status'] = 'RECONCILIATION_REQUIRED'
        position['quantity'], position['amount'] = str(quantity), str(amount)
        result.append(position)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Directory containing source manifest.json and records.sqlite')
    parser.add_argument('output', type=Path, help='New local JSON file for the review')
    parser.add_argument('--bindings', type=Path, help='Existing native source-to-MM master journal')
    args = parser.parse_args()
    manifest = json.loads((args.source / 'manifest.json').read_text())
    database = args.source / 'records.sqlite'
    digest = hashlib.sha256()
    with database.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != manifest.get('databaseSha256'):
        raise ValueError('Source database hash does not match its manifest')
    if manifest.get('snapshotDate') != '2026-10-08':
        raise ValueError('Expected the authorized 2026-10-08 snapshot')
    db = sqlite3.connect(database.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        positions = prepare_positions((json.loads(content) for (content,) in
            db.execute("SELECT content FROM records WHERE kind='BALANCE'")),
            source_organization=manifest['organizationSourceId'], snapshot_date=manifest['snapshotDate'])
    finally:
        db.close()
    if args.bindings:
        bind_materials(positions, json.loads(args.bindings.read_text()))
    report = {'snapshotDate': manifest['snapshotDate'], 'companyId': manifest['companyId'],
        'databaseSha256': digest.hexdigest(), 'accounts': sorted(ACCOUNTS),
        'positions': positions, 'counts': dict(Counter(position['status'] for position in positions)),
        'posted': False}
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print(json.dumps({'positions': len(positions), 'counts': report['counts'], 'posted': False}))


if __name__ == '__main__':
    main()
