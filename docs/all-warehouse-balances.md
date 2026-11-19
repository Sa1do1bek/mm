# Navoiyazot warehouse account balances

Import the complete warehouse accounting snapshot from the restored 2026-10-08 1C database. Select stock by its material/product and warehouse dimensions, not by an account-prefix allowlist. Preserve signed quantities, zero-quantity values, source accounts, material types, currency and additional dimensions.

The existing owned-material opening remains authoritative for operational availability. It must retain its row identities, quantities, costs, reservations and batches. Additional account balances are stored as typed warehouse-account positions: custody, materials in use, equipment, asset acquisition, finished products and shortages retain their meaning instead of becoming freely available owned stock.

One complete company-scoped account snapshot and its exact historical accounting movements drive the reports. Existing 10xx opening/history rows must not be counted a second time. Subsequent real MM movements remain included. Opening and past closing balances are reconstructed from the verified snapshot and signed source movements. The three purchased-fixed-asset entries in warehouse 129.0 must show 1550, 310 and 315 pieces, including their zero book values.

Imports use immutable source keys and content hashes, bounded transactional chunks, replay checks, tenant/company ownership checks and a full-content completion gate. A changed duplicate is rejected. Unresolved references retain source identities and readable labels; no invented physical location, unit, serial number or asset instance is created.

Reports and CSV/XLSX exports show and filter the source account and source type. Finished products remain distinguishable from materials. Russian labels use «Счёт учёта», «Вид номенклатуры», «Все счета», «Готовая продукция» and explicit labels for unspecified source references. Negative and value-only positions remain visible. Totals keep quantities separate by unit and explain the account scope.

Verification includes all source balance keys and account/unit totals, stored content hashes and replay/conflict checks; before/after fingerprints of the existing operational opening; current and historical reports, receipts/issues, account/type filters, Russian CSV/XLSX and desktop/mobile UI. Deploy reviewed code, perform the authorized company import, then reconcile the live system.
