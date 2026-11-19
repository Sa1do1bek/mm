"""Execute the real fixed-scope adoption SQL in a disposable PostgreSQL schema."""

from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import unittest
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "src/main/resources/db/migration"
SCOPE = json.loads((ROOT / "src/test/resources/packaging-legacy-adoption-scope.json").read_text())
DSN = os.environ.get("MM_TEST_POSTGRES_DSN")
COMPANY = "753e618e-8d47-4efd-a41e-df8311b83d2f"
IMPORT = "42c00ffd-ddac-5bfc-86bf-93924ac6c036"
ADOPT = (MIGRATIONS / "V60__adopt_1c_returnable_packaging_balances.sql").read_text()


@unittest.skipUnless(DSN, "MM_TEST_POSTGRES_DSN must name a disposable database")
class PackagingLegacyAdoptionTest(unittest.TestCase):
    def sql(self, sql, check=True):
        result = subprocess.run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", DSN],
            input="SET search_path TO " + self.schema + ";\n" + sql, text=True, capture_output=True)
        if check and result.returncode:
            self.fail(result.stderr)
        return result

    def setUp(self):
        self.schema = "packaging_adoption_" + uuid4().hex
        self.sql(f"CREATE SCHEMA {self.schema}; SET search_path TO {self.schema};" + """
            CREATE TABLE companies(id uuid PRIMARY KEY,tenant_id text,code text);
            CREATE TABLE materials(id uuid PRIMARY KEY,tenant_id text,company_id uuid,material_type text,
                material_subtype text,tracking_mode text,serial_tracking_policy text,description text,
                name text,ikpu_code text,version bigint DEFAULT 0,updated_at timestamp,updated_by text,
                is_delete boolean DEFAULT false,CONSTRAINT materials_subtype_business_type_check CHECK(material_type='TMB'));
            CREATE TABLE stock_batches(id uuid PRIMARY KEY,tenant_id text,company_id uuid,material_id uuid,
                warehouse_id uuid,unit_cost numeric(19,4),quantity numeric(22,7),is_delete boolean DEFAULT false);
            CREATE TABLE stock_movements(id uuid PRIMARY KEY,tenant_id text,company_id uuid,material_id uuid,
                warehouse_id uuid,stock_batch_id uuid,status text,quantity numeric(22,7),value numeric(19,4),is_delete boolean DEFAULT false);
            CREATE TABLE stock_balances(id uuid PRIMARY KEY,tenant_id text,company_id uuid,material_id uuid,
                warehouse_id uuid,stock_batch_id uuid,storage_location_id uuid,storage_bin_id uuid,
                quantity numeric(22,7),available_quantity numeric(22,7),reserved_quantity numeric(22,7) DEFAULT 0,
                quarantine_quantity numeric(22,7) DEFAULT 0,total_value numeric(19,4),stock_category text,
                owner_party_id uuid,is_delete boolean DEFAULT false);
            CREATE TABLE exact_opening_imports(id uuid PRIMARY KEY,tenant_id text,company_id uuid,status text);
            CREATE TABLE exact_opening_rows(id uuid PRIMARY KEY,import_id uuid,source_row_id text,
                movement_id uuid UNIQUE,material_source_id text,account_code text);
            """)
        for table in ("suppliers", "purchase_order_items", "goods_receipts", "goods_receipt_lines",
                      "warehouse_posting_batches", "warehouses", "stock_movement_documents"):
            self.sql("CREATE TABLE " + table + "(id uuid PRIMARY KEY);")
        for table in ("inventory_cost_layers", "stock_cost_movements", "stock_movement_allocations", "stock_movement_allocation_cost_layers"):
            self.sql("CREATE TABLE " + table + "(id uuid PRIMARY KEY,tenant_id text,company_id uuid,quantity numeric(22,7),value numeric(19,4));")
        for version in ("V51__returnable_packaging.sql", "V55__returnable_packaging_register_completion.sql", "V59__returnable_packaging_legacy_opening.sql"):
            self.sql((MIGRATIONS / version).read_text())
        commands = [f"INSERT INTO companies VALUES('{COMPANY}','default','NAVOIYAZOT-1C-20261008');",
                    f"INSERT INTO exact_opening_imports VALUES('{IMPORT}','default','{COMPANY}','COMPLETED');"]
        seen_materials, seen_warehouses = set(), set()
        self.expected_value = Decimal(0)
        for index, row in enumerate(SCOPE):
            material, warehouse, batch, balance = (row[key] for key in ("materialId", "warehouseId", "batchId", "balanceId"))
            if material not in seen_materials:
                commands.append(f"INSERT INTO materials VALUES('{material}','default','{COMPANY}','TMB','MATERIAL','QUANTITY','NONE',"
                    f"'Catalog54:{row['sourceId']}','Legacy packaging','UNRESOLVED-MXIK',0,NULL,NULL,false);")
                seen_materials.add(material)
            if warehouse not in seen_warehouses:
                commands.append(f"INSERT INTO warehouses VALUES('{warehouse}');")
                seen_warehouses.add(warehouse)
            value = Decimal(0) if index == 0 else Decimal("12345.6700") + Decimal(index) / 10000
            self.expected_value += value
            movement = str(uuid4())
            commands.extend([
                f"INSERT INTO stock_batches VALUES('{batch}','default','{COMPANY}','{material}','{warehouse}',0.3333,{row['quantity']},false);",
                f"INSERT INTO stock_balances VALUES('{balance}','default','{COMPANY}','{material}','{warehouse}','{batch}',NULL,NULL,"
                    f"{row['quantity']},{row['quantity']},0,0,{value},'OWNED',NULL,false);",
                f"INSERT INTO stock_movements VALUES('{movement}','default','{COMPANY}','{material}','{warehouse}','{batch}',"
                    f"'POSTED',{row['quantity']},{value},false);",
                f"INSERT INTO exact_opening_rows VALUES('{uuid4()}','{IMPORT}','source-{index}','{movement}','{row['sourceId']}','{row['accountCode']}');",
            ])
        self.sql("\n".join(commands))

    def tearDown(self):
        self.sql("SET search_path TO public; DROP SCHEMA " + self.schema + " CASCADE;")

    def adopt(self, check=True):
        return self.sql("BEGIN;\n" + ADOPT + "\nCOMMIT;", check)

    def test_exact_values_quantities_lineage_and_unknown_state_survive_and_replay_is_safe(self):
        self.adopt()
        result = self.sql("""
            SELECT json_build_object('materials',(SELECT count(*) FROM materials WHERE material_subtype='RETURNABLE_PACKAGING'),
                'lots',(SELECT count(*) FROM returnable_packaging_lots),
                'events',(SELECT count(*) FROM returnable_packaging_events WHERE event_type='OPENING_ADOPTED' AND to_state='UNKNOWN'),
                'same',(SELECT bool_and(l.opening_value=b.total_value AND l.unknown_quantity=b.quantity
                    AND l.received_quantity=b.quantity AND l.stock_batch_id=b.stock_batch_id
                    AND l.supplier_id IS NULL AND l.content_material_id IS NULL AND l.empty_quantity=0)
                    FROM returnable_packaging_lots l JOIN stock_balances b ON b.id=l.legacy_stock_balance_id),
                'audit',(SELECT stock_before=stock_after AND material_before_md5=material_after_md5 FROM returnable_packaging_legacy_migrations),
                'value',(SELECT sum(opening_value)::text FROM returnable_packaging_lots),
                'mxik',(SELECT bool_and(ikpu_code='UNRESOLVED-MXIK') FROM materials));
            """)
        data = json.loads(result.stdout)
        self.assertEqual((data["materials"], data["lots"], data["events"]), (112, 159, 159))
        self.assertTrue(data["same"] and data["audit"] and data["mxik"])
        self.assertEqual(Decimal(data["value"]), self.expected_value)
        self.adopt()
        self.assertEqual(self.sql("SELECT count(*)||':'||max(version) FROM materials;").stdout.strip(), "112:1")
        self.assertEqual(self.sql("SELECT count(*) FROM returnable_packaging_lots;").stdout.strip(), "159")

    def test_changed_quantity_rolls_back_without_reclassification_or_new_lots(self):
        self.sql(f"UPDATE stock_balances SET quantity=quantity+0.0000007 WHERE id='{SCOPE[0]['balanceId']}';")
        result = self.adopt(False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stock quantities, ownership or original batch lineage changed", result.stderr)
        self.assertEqual(self.sql("SELECT count(*) FROM materials WHERE material_subtype='MATERIAL';").stdout.strip(), "112")
        self.assertEqual(self.sql("SELECT count(*) FROM returnable_packaging_lots;").stdout.strip(), "0")

    def test_existing_reservation_is_not_silently_reclassified_as_available_packaging(self):
        self.sql(f"UPDATE stock_balances SET reserved_quantity=1,available_quantity=quantity-1 WHERE id='{SCOPE[0]['balanceId']}';")
        self.assertNotEqual(self.adopt(False).returncode, 0)
        self.assertEqual(self.sql("SELECT count(*) FROM returnable_packaging_events;").stdout.strip(), "0")

    def test_missing_source_lineage_and_wrong_company_are_rejected(self):
        self.sql("DELETE FROM exact_opening_rows WHERE source_row_id='source-0';")
        self.assertNotEqual(self.adopt(False).returncode, 0)
        self.sql("UPDATE companies SET code='OTHER';")
        self.assertIn("company identity differs", self.adopt(False).stderr)


if __name__ == "__main__":
    unittest.main()
