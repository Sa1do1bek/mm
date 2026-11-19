# Inventory order scoped extraction: backend and frontend handoff

Date: 2026-11-13

## Implemented

- The backend derives analysis scope from the authenticated company and active departments whose `managerEmployee` is the current user's employee. `ADMIN` receives `ALL`; a department head receives all departments they manage. The browser cannot submit department IDs.
- Version 2 job requests freeze the scope, file hashes and version in the stored request and request hash. The existing multipart AI endpoint receives this scope in `metadata` with tenant and company headers.
- Version 2 results use `chairAssignments`, `workItems` and `commissionRosters`. Validation checks schema, scope echo, authorized department IDs, chair department names, source keys and links, source file IDs, citations, member evidence and size limits. Failures identify the JSON path.
- Old version 1 jobs remain readable for administrators. Department heads cannot start version 1 analysis or read previously completed unscoped inventory AI results or orders.
- Commission recommendations use documented, complete rosters with at least two members. Chair assignments and working groups cannot produce a create recommendation. ERP match candidates with zero overlap are omitted.
- Version 2 job and order reads return a small header and item counts. `GET /api/inventory/orders/{id}/pages/{section}` pages `chair-assignments`, `work-items` and `commission-rosters` with `offset`, `limit` (1–100, default 20) and optional `search`. Every page rechecks tenant, company and order access. Department results are grouped by department in stable order.
- The frontend shows the effective scope, job status, no-match or ambiguous scope, and separate paged sections. Work item search and selection are retained while navigating pages. Commission creation remains editable and uses backend-resolved members.

## API and rollout

1. `GET /api/inventory/ai-drafts/order-analysis-scope` returns `{enabled, scope}`. The scope is server computed and includes target department names and aliases.
2. `POST /api/inventory/ai-drafts/{id}/scoped-order-analysis-jobs` starts version 2 analysis when the server gate is enabled.
3. Set `MM_AI_INVENTORY_SCOPED_ORDER_ENABLED=true` only after the AI service accepts the version 2 request and returns the agreed fixtures. The default is `false`.
4. The frontend's existing `VITE_INVENTORY_DOCUMENT_AI_ENABLED` gate must also be enabled. Administrators can still use the legacy version 1 route while the new server gate is off. Department heads see analysis as unavailable until version 2 is enabled.

## AI team dependency

The AI service must implement the contract in [inventory-order-scoped-extraction-ai-handoff-uz.md](inventory-order-scoped-extraction-ai-handoff-uz.md). It must return only authorized department content for `DEPARTMENTS`; the MM backend validates structured scope but cannot prove that arbitrary prose or evidence does not mention another department. The AI team must provide raw fixtures for `ALL`, one and multiple departments, ambiguous names and no match before enabling the gate.

No production end-to-end result is claimed until those fixtures and the deployed AI service are available.
