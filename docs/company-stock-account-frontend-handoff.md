# Company stock accounts: frontend handoff

The company manages two accounts in **Inventory Accounting Settings**. Material groups do not expose or override either account.

- `defaultStockAccountId`: shared by ordinary materials in every material group.
- `returnablePackagingStockAccountId`: used by company-owned returnable packaging.

The existing `GET` and `PUT` contracts at `/api/settings/inventory-accounting` return the ID, code, and name triplet for both settings.

Load selector options through `GET /api/stock-account-resolution/eligible-accounts`. The backend returns active, postable current-company accounts and excludes custody account `002`:

```json
[
  {
    "id": "<account UUID>",
    "accountCode": "1060",
    "accountName": "Company stock"
  }
]
```

Receipt users do not select an account. `GET /api/stock-account-resolution/materials/{materialId}` returns the appropriate company setting for read-only display. Ordinary materials resolve to the shared default; company-owned returnable packaging resolves to its separate account. Supplier-owned returnable packaging remains fixed to custody account `002`.

The resolved code is stored as an immutable stock-movement snapshot. Changing the setting affects only future receipts; reversals retain the original code.
