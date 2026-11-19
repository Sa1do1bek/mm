# Inventory material subtypes

The accounting/business type remains `TMB`. Its `materialSubtype` values are
`MATERIAL` (Материалы), `EQUIPMENT` (Оборудование), and `ANIMALS` (Животные).
Services and fixed assets have no inventory subtype. Procurement category snapshots,
MXIK GOODS compatibility, stock tracking, stock balances and valuation continue to
use the TMB parent classification.

`POST /api/materials` and `PUT /api/materials/{id}` accept `materialSubtype`.
Omitting it defaults new TMB records to MATERIAL and preserves it on existing TMB
records. A transition to another business type clears it. An explicitly supplied
subtype for a service/fixed asset is rejected with localized validation.
`GET /api/materials?materialSubtype=EQUIPMENT&materialType=TMB` filters the catalog;
MATERIAL also includes legacy null TMB subtypes. Responses and the material command
view header include the subtype. `/api/materials/enums/material-subtypes` supplies
Russian, English and Uzbek labels scoped to this enumeration.

All four warehouse reports accept the optional `materialSubtype` parameter on
both `/api/warehouse-reports` and `/api/warehouse-reports/export`. The selection
restricts rows, pagination counts, totals and stock reconciliation consistently.
MATERIAL includes legacy null TMB subtypes and excludes equipment, animals and
fixed assets. With no subtype selected, the existing report scope is preserved.
Historical movements use their mapped catalog classification; unknown legacy
references remain in unfiltered reports and are excluded from a selected subtype.
Report responses echo the applied subtype, and Excel/CSV headers identify it in
Russian, English or Uzbek without changing the report's accounting columns.

V10 adds the column and restores 3,486 equipment and 3,195 animal catalog entries
for the default tenant's NAVOIYAZOT-1C-20261008 company. It validates the exact
Catalog54 source ID and the original enum reference/name in the preserved source
JSON, supporting both import marker formats. Malformed evidence, other companies,
other tenants, services and fixed assets are excluded from reclassification.
Original names, status, source evidence, quantities and monetary values are retained.
Corrected rows receive a version increment and an explicit migration updater.
The original import manifests/journals remain immutable.

Verification:

- Material policy, search/filter, imported create/update and command-view tests.
- PostgreSQL regression with malformed/cross-company evidence and exact quantities.
- The same migration against all 6,681 actual imported source descriptions.
- PostgreSQL report and endpoint tests covering every report type, filtered totals,
  tenant/company scope, reconciliation, mapped history and Excel/CSV metadata.
- `migration-1c/verify_material_subtypes.py --live before|after` on the local migration
  workspace checks every completed mapping and verifies deployed subtypes, Russian
  labels, the total catalog count and the unchanged exact opening response via GETs.

Run the SQL regression against a disposable PostgreSQL database:

```sh
MM_TEST_POSTGRES_DSN=postgresql://127.0.0.1:55441/postgres python3 -m unittest scripts/test_material_subtype_migration.py -v
```

Set MM_TEST_1C_PREPARED to the private prepared/materials.jsonl path to additionally
verify the actual imported evidence. Private source data is not committed.
