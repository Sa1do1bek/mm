import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from decimal import Decimal

from finished_goods_opening import prepare_positions, bind_materials


def row(key, account, quantity, amount, *, warehouse='warehouse', material='material', unit='ton', **extra):
    return {'sourceKey': key, 'accountCode': account, 'quantity': quantity, 'amount': amount,
            'warehouseSourceId': warehouse, 'materialSourceId': material, 'materialReferenceType': '00000037',
            'uom': unit, 'stockCategory': 'FINISHED_PRODUCTS', **extra}


class FinishedGoodsOpeningTest(unittest.TestCase):
    def test_groups_all_four_accounts_by_warehouse_material_and_unit(self):
        positions = prepare_positions([
            row('a', '2812', '7212.3330360', '89139138603.85'),
            row('b', '2813', '-1764.0000000', '0.00'),
            row('c', '2811', '7632.3200000', '36413884048.88', warehouse='other'),
            row('d', '2812', '-227.7800000', '0.00', warehouse='other'),
            row('e', '2813', '-820.2000000', '0.00', warehouse='other'),
            row('f', '2810', '1', '2', unit='kg'),
        ])
        indexed = {(p['warehouseSourceId'], p['uom']): p for p in positions}
        self.assertEqual(Decimal(indexed['warehouse', 'ton']['quantity']), Decimal('5448.333036'))
        self.assertEqual(Decimal(indexed['warehouse', 'ton']['amount']), Decimal('89139138603.85'))
        self.assertEqual(Decimal(indexed['other', 'ton']['quantity']), Decimal('6584.34'))
        self.assertEqual(Decimal(indexed['warehouse', 'kg']['quantity']), Decimal('1'))
        self.assertEqual(indexed['warehouse', 'ton']['sourceKeys'], ['a', 'b'])

    def test_negative_or_value_only_positions_are_not_opening_stock(self):
        positions = prepare_positions([row('a', '2812', '-1', '3'),
                                       row('b', '2810', '0', '10', material='other')])
        status = {p['materialSourceId']: p['status'] for p in positions}
        self.assertEqual(status['material'], 'NEGATIVE_REVIEW')
        self.assertEqual(status['other'], 'VALUE_ONLY_REVIEW')

    def test_does_not_include_production_or_commission_accounts(self):
        self.assertEqual(prepare_positions([row('a', '2840', '5', '2'), row('b', '2830', '7', '3')]), [])

    def test_rejects_duplicate_source_keys_and_nonfinite_decimals(self):
        with self.assertRaises(ValueError):
            prepare_positions([row('a', '2810', '2', '2'), row('a', '2811', '3', '3')])
        with self.assertRaises(ValueError):
            prepare_positions([row('a', '2812', 'NaN', '1')])

    def test_unexpected_extra_dimensions_are_flagged(self):
        positions = prepare_positions([row('a', '2810', '1', '1', additionalDimensions={
            'dimension1_rrref': 'material', 'dimension2_rrref': 'warehouse', 'dimension3_rrref': 'owner'})])
        self.assertEqual(positions[0]['status'], 'DIMENSIONS_REVIEW')

    def test_foreign_currency_dimension_requires_review(self):
        positions = prepare_positions([row('a', '2810', '1', '1', additionalDimensions={'currencySourceId': 'other-currency'})])
        self.assertEqual(positions[0]['status'], 'DIMENSIONS_REVIEW')

    def test_source_scope_mismatch_is_rejected(self):
        with self.assertRaises(ValueError):
            prepare_positions([row('a', '2810', '1', '1', organizationSourceId='other', sourceDate='2026-10-08')],
                              source_organization='company', snapshot_date='2026-10-08')

    def test_material_bindings_are_type_scoped_and_missing_bindings_stay_unresolved(self):
        positions = prepare_positions([row('a', '2810', '1', '2'),
                                       row('b', '2811', '1', '2', material='missing')])
        bind_materials(positions, {'_reference55:material': 'mm-material', '_reference54:missing': 'wrong-catalog'})
        indexed = {p['materialSourceId']: p for p in positions}
        self.assertEqual(indexed['material']['materialId'], 'mm-material')
        self.assertEqual(indexed['missing']['materialId'], None)

    def test_conflicting_direct_warehouse_bindings_require_review(self):
        positions = prepare_positions([row('a', '2810', '1', '1', warehouseId='mm-1'),
                                       row('b', '2811', '1', '1', warehouseId='mm-2')])
        self.assertEqual(positions[0]['status'], 'MAPPING_REVIEW')

    def test_cli_checks_snapshot_hash_and_never_overwrites_review(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'source'
            source.mkdir()
            db = sqlite3.connect(source / 'records.sqlite')
            db.execute('CREATE TABLE records(kind TEXT, content TEXT)')
            db.execute('INSERT INTO records VALUES (?, ?)', ('BALANCE', json.dumps(row('a', '2810', '1', '2',
                organizationSourceId='org', sourceDate='2026-10-08'))))
            db.commit()
            db.close()
            digest = hashlib.sha256((source / 'records.sqlite').read_bytes()).hexdigest()
            (source / 'manifest.json').write_text(json.dumps({'databaseSha256': digest,
                'snapshotDate': '2026-10-08', 'companyId': 'company', 'organizationSourceId': 'org'}))
            output = Path(directory) / 'review.json'
            command = [sys.executable, str(Path(__file__).with_name('finished_goods_opening.py')), str(source), str(output)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            result = json.loads(output.read_text())
            self.assertFalse(result['posted'])
            self.assertEqual(result['positions'][0]['quantity'], '1')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            (source / 'manifest.json').write_text(json.dumps({'databaseSha256': 'bad', 'snapshotDate': '2026-10-08'}))
            self.assertNotEqual(subprocess.run(command[:-1] + [str(Path(directory) / 'other.json')], capture_output=True).returncode, 0)
            self.assertFalse((Path(directory) / 'other.json').exists())


if __name__ == '__main__':
    unittest.main()
