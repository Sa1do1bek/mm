"""PostgreSQL regression for V10; set MM_TEST_POSTGRES_DSN to a disposable database."""

import json
import os
from pathlib import Path
import subprocess
import unittest
from uuid import uuid4

MIGRATION = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration/V10__material_tmb_subtypes.sql"
DSN = os.environ.get("MM_TEST_POSTGRES_DSN")


@unittest.skipUnless(DSN, "MM_TEST_POSTGRES_DSN is required")
class MaterialSubtypeMigrationTest(unittest.TestCase):
    def sql(self, text, check=True):
        result = subprocess.run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", DSN],
                                input=text, text=True, capture_output=True)
        if check and result.returncode:
            self.fail(result.stderr)
        return result

    def setUp(self):
        self.schema = "subtype_test_" + uuid4().hex
        self.company, self.other_company = str(uuid4()), str(uuid4())
        self.sql(f"""
            CREATE SCHEMA {self.schema}; SET search_path TO {self.schema};
            CREATE TABLE companies (id uuid, tenant_id text, code text);
            INSERT INTO companies VALUES ('{self.company}', 'default', 'NAVOIYAZOT-1C-20261008'),
                ('{self.other_company}', 'default', 'OTHER-COMPANY');
            CREATE TABLE materials (id uuid, tenant_id text, company_id uuid, material_type text,
                description text, version bigint DEFAULT 0, updated_at timestamp, updated_by text);
            CREATE TABLE stock_balances (material_id uuid, quantity numeric(24,7), value numeric(24,2));
        """)

    def tearDown(self):
        self.sql(f"DROP SCHEMA {self.schema} CASCADE;")

    def description(self, source_id, kind, enum_ref, prefix=""):
        return prefix + "Catalog54:" + source_id + "\n1C source evidence: " + json.dumps({
            "source_id": source_id, "item_kind_name": kind, "item_kind_source_id": enum_ref,
        }, ensure_ascii=False)

    def insert(self, description, company=None, tenant="default", business_type="TMB"):
        identifier = str(uuid4())
        value = description.replace("'", "''")
        self.sql(f"SET search_path TO {self.schema}; INSERT INTO materials VALUES ("
                 f"'{identifier}', '{tenant}', '{company or self.company}', '{business_type}',"
                 f"'{value}', 5, NULL, NULL);")
        return identifier

    def migrate(self):
        self.sql(f"SET search_path TO {self.schema};\n" + MIGRATION.read_text())

    def test_source_identity_scoping_defaults_and_exact_stock_are_preserved(self):
        equipment_ref = "aa364d2c36cb56724241db6745169071"
        animal_ref = "8a7701486f0764b5414e5cd892150a1f"
        equipment = self.description("1" * 32, "Оборудование", equipment_ref)
        animals = self.description("2" * 32, "Животные", animal_ref)
        expected = {}
        expected[self.insert(equipment)] = ("EQUIPMENT", 6)
        expected[self.insert(equipment + "\n[1c-mm-migration:" + "a" * 64 + ":Catalog54:" + "1" * 32 + "]")] = ("EQUIPMENT", 6)
        expected[self.insert(
            "[1c-mm-migration:" + "a" * 64 + ":Catalog54:" + "2" * 32 + "]\n" + animals)] = ("ANIMALS", 6)
        expected[self.insert(equipment, company=self.other_company)] = ("MATERIAL", 5)
        expected[self.insert(equipment, tenant="other-tenant")] = ("MATERIAL", 5)
        expected[self.insert(equipment, business_type="SERVICES")] = (None, 5)
        expected[self.insert(equipment, business_type="FIXED_ASSET")] = (None, 5)
        expected[self.insert(equipment.replace('"source_id": "' + "1" * 32, '"source_id": "' + "3" * 32))] = ("MATERIAL", 5)
        expected[self.insert("Catalog54:" + "1" * 32 + "\n1C source evidence: {" + equipment_ref)] = ("MATERIAL", 5)
        expected[self.insert(self.description("4" * 32, "Животные", equipment_ref))] = ("MATERIAL", 5)
        expected[self.insert("Native equipment with no 1C source identity")] = ("MATERIAL", 5)
        first_id = next(iter(expected))
        self.sql(f"SET search_path TO {self.schema}; INSERT INTO stock_balances VALUES "
                 f"('{first_id}', 0.0000007, 0.38);")
        self.migrate()
        result = self.sql(f"SET search_path TO {self.schema}; SELECT json_agg(json_build_object("
                          "'id',id,'subtype',material_subtype,'version',version)) FROM materials;")
        actual = {row["id"]: (row["subtype"], row["version"]) for row in json.loads(result.stdout)}
        self.assertEqual(actual, expected)
        stock = self.sql(f"SET search_path TO {self.schema}; SELECT quantity::text || ',' || value::text FROM stock_balances;")
        self.assertEqual(stock.stdout.strip(), "0.0000007,0.38")
        invalid = self.sql(f"SET search_path TO {self.schema}; UPDATE materials SET material_type='SERVICES' "
                           "WHERE material_subtype='EQUIPMENT';", check=False)
        self.assertNotEqual(invalid.returncode, 0)

    @unittest.skipUnless(os.environ.get("MM_TEST_1C_PREPARED"), "No private 1C fixture supplied")
    def test_actual_imported_equipment_and_animals_evidence(self):
        values = []
        def rows():
            with Path(os.environ["MM_TEST_1C_PREPARED"]).open() as stream:
                for line in stream:
                    yield json.loads(line)
        for row in rows():
            description = row["payload"]["description"]
            evidence = json.loads(description.split("\n1C source evidence: ", 1)[1])
            if evidence.get("item_kind_name") not in ("Оборудование", "Животные"):
                continue
            # Cover the legacy suffix and the later bulk-import prefix forms.
            marker = "[1c-mm-migration:" + "a" * 64 + ":Catalog54:" + row["source_id"] + "]"
            description = description + "\n" + marker if len(values) % 2 else marker + "\n" + description
            values.append(f"('{uuid4()}', 'default', '{self.company}', 'TMB', "
                          "'" + description.replace("'", "''") + "', 0, NULL, NULL)")
        self.assertEqual(len(values), 6681)
        self.sql(f"SET search_path TO {self.schema}; INSERT INTO materials VALUES " + ",".join(values) + ";")
        self.migrate()
        result = self.sql(f"SET search_path TO {self.schema}; SELECT material_subtype || ':' || count(*) "
                          "FROM materials GROUP BY material_subtype ORDER BY material_subtype;")
        self.assertEqual(result.stdout.splitlines(), ["ANIMALS:3195", "EQUIPMENT:3486"])


if __name__ == "__main__":
    unittest.main()
