# Inventory order extraction by user scope: implementation plan

Date: 2026-11-13
Status: backend and frontend implementation prepared behind a default-off gate; AI version 2 pending. See [implementation report](inventory-order-scoped-extraction-implementation-report.md).

## Goal and business rules

The same uploaded order has two views. A department head receives only the order header needed for context and the chair assignment, work items, and relevant commission roster for the departments they are authorized to lead. An `ADMIN` user can request the full company order. A user cannot select `ALL` or supply arbitrary department IDs to enlarge their scope.

Annex 2 of order №1749 lists 99 inventory objects and their appointed local chairs. These are **chair assignments**, not 99 complete commission rosters. The central and other named commissions and working groups with listed members are separate. A partial local roster cannot produce a ready-to-create commission request. The source document and cited page remain visible to an authorized reviewer.

Scope is derived from the authenticated user, tenant, company, active department leadership, and valid department/warehouse mappings at job creation. Record the resolved scope in the immutable job request. If no authorized department can be resolved, reject the job before invoking AI. If one head leads multiple departments, include all of them. Company and tenant boundaries apply to every lookup and result read.

The AI can read the entire PDF to locate one department's entries, but its output must contain only that department's information. If the requirement literally prohibits the AI service from processing other pages or text, the backend needs document redaction or segmentation before submission; this is a separate, more costly security design. The contract below guarantees scoped output and backend enforcement.

## Current implementation and gaps

| Area | Current behavior | Change |
| --- | --- | --- |
| Request | `HttpMmAiClient` sends `analysisId`, language, analysis versions, file metadata, and files; headers carry tenant and company. | Add server-computed scope and a new contract version. |
| Authorization | Drafts and jobs are restricted to their owner; a `DEPARTMENT_HEAD` can start extraction. | Resolve and freeze the head's authorized departments; never trust a browser-supplied scope. |
| Result | Job result and order payload contain the whole AI JSON. | Validate scoped output before persistence; expose typed, paged reads for full orders. |
| Commission matching | Iterates all `commissionAnalysis.commissions`, including partial local chair entries. | Match only real, sufficiently documented commission rosters. Keep chair assignments in their own collection. |
| Validation | Root warnings max 20; field citation quote max 400; commission count max 300; total members max 1000. | Agree versioned bounds with AI and report exact JSON path, allowed and actual values. |
| Observed response | The 2026-11-13 response had 106 AI commission objects, including 99 local derived entries, 103 work items, and two 417-character date quotes. | Reclassify the 99 entries; reject or repair oversized citations according to the agreed contract. |

The AI developer's 2026-11-12 response-check note refers to a different analysis ID, with 24 root warnings and no `orderAnalysis`. It is not evidence about the newer 2026-11-13 response, which has 20 root warnings and `orderAnalysis`.

## Contract decision

Introduce `orderAnalysisVersion: 2` for this change. Retain version 1 for existing jobs and clients; never reinterpret saved version 1 payloads as version 2. The backend sends an immutable, authoritative scope alongside the current multipart metadata:

```json
{
  "analysisId": "<UUID>",
  "language": "uz",
  "commissionAnalysisVersion": 2,
  "orderAnalysisVersion": 2,
  "scope": {
    "mode": "DEPARTMENTS",
    "companyId": "<UUID>",
    "departmentIds": ["<UUID>"],
    "targets": [
      {"departmentId": "<UUID>", "name": "<ERP department name>", "aliases": ["<verified unit code/name>"], "warehouseIds": ["<UUID>"]}
    ]
  },
  "documents": [{"fileId": "<UUID>", "fileName": "1749.pdf", "contentType": "application/pdf", "sha256": "<64 hex>"}]
}
```

For admin, `scope.mode` is `ALL` and `departmentIds`/`targets` are empty. The backend sets the tenant/company headers and verifies `companyId` against the current company. The AI service must treat scope as part of its idempotency key: same `analysisId` with changed scope, files, or versions is a conflict; a changed request uses a new ID. No user roles, ERP commission IDs, or arbitrary browser hints are authoritative at the AI service.

The response must echo `scope.mode`, scoped department IDs, and version. Separate `chairAssignments[]` from `commissionRosters[]` and `workItems[]`. Each assignment includes source key, Annex 2 item number, object mention, chair name/position, citations, and `otherMembersRequireLocalOrder`. A roster includes kind, members, completeness, citations, and a `createEligible` value determined by documented membership; AI does not assign ERP IDs. Use stable source keys within retries. `ALL` returns full order coverage; `DEPARTMENTS` returns only relevant entries plus a small global header, with `scopeResolution: MATCHED | AMBIGUOUS | NOT_FOUND` and evidence. Never return unrelated names in warnings, citations, free text, or fallback fields. Ambiguous matching yields review, not a guessed department.

Bounds are a wire-contract concern, distinct from UI page size. Agree exact maximum counts and citation length with AI and enforce them identically. For the current document, the backend's 400-character citation limit must either be honored by AI or deliberately raised in both contract and validator. Do not silently truncate evidence. `NEEDS_REVIEW`, missing optional values, and partial rosters are valid business outcomes.

## Backend work

1. **Scope resolver:** map authenticated user to active department head assignments (check `User.department`, `Employee.department`, `Department.managerEmployee`, and `RoleDataScopeService`; define a single authority rule). Validate tenant/company and active department status. For admins allow `ALL`; for department heads derive all led departments. Warehouse mappings add context but do not grant another department's chair assignment.
2. **Job snapshot and AI client:** persist scope, document hashes, version, requester, and request hash before the asynchronous call. Send scope in multipart metadata; carry it through retry. Prevent job reuse across scope changes. Keep owner and tenant/company checks on reads.
3. **Validator:** validate version, scope echo, source references, unique keys, coverage, lists, citations, and warnings. Compare returned department IDs against the frozen allowlist. Detect unrelated names in structured assignments and fail closed. Log the failing JSON path, expected limit, actual count/type, and analysis ID; avoid dumping entire documents in normal logs.
4. **Normalization and storage:** save the validated full admin result or scoped department result. Store chair assignments separately from commission rosters in the public response model. Retain raw versioned payload for audit. A full result must not be served through a department-scoped endpoint.
5. **Commission matching:** evaluate only rosters with enough named members and appropriate type; show incomplete rosters as needing a local appointment order. Calculate existing ERP matches within the same tenant/company and permitted scope. Never offer `Create commission` for a chair-only assignment.
6. **Read API:** return a small job/order summary first. Add stable, server-paged `chair-assignments`, `work-items`, and `commission-rosters` endpoints (`limit` default 20, maximum 100, opaque cursor or stable offset with total). Apply authorization and scope on every page. Keep existing `/jobs/{id}` and `/orders/{id}` compatibility deliberately versioned; do not let an old full-payload route bypass scope.
7. **Operational limits:** use asynchronous jobs, bounded response/request size, timeout and retry policy, and document count/byte limits. A response around 480 KB is acceptable as one backend-to-AI result; UI pagination does not impose a 20-item AI limit. If future orders exceed the agreed transport cap, add numbered AI chunks under one analysis ID and assemble them before `COMPLETED`.

## Frontend work

1. Show the effective scope before analysis: `All company` for admins, or the department names supplied by the backend for a head. Do not offer a client-side switch that can broaden scope.
2. Keep multiple file upload and background-job progress. Show `NEEDS_REVIEW` as a review state. Show a clear `No matching assignment` or `Ambiguous department` result without displaying other departments' data.
3. Use the paged endpoints for the admin detail page: order header, Annex 2 chair assignments, work items, and named commission rosters in separate sections. Retain search and pagination state across review.
4. Give a department head a scoped detail page with only the assigned unit entries and relevant evidence. If a head leads multiple departments, group results by department.
5. Show existing ERP commission matches only for eligible rosters. Build an editable create request from backend-resolved member drafts only after review; do not create from partial local chair assignments.

## Delivery sequence and acceptance checks

1. AI and MM agree the version 2 request/response fixtures, bounds, and `NOT_FOUND`/`AMBIGUOUS` behavior. Use №1749 plus a small order with two similarly named units.
2. Backend lands the scope resolver, validator, storage/projections, matching correction, and paged APIs behind a version gate. Keep version 1 jobs readable.
3. AI adds scope-aware extraction and sends fixtures for `ALL`, one department, multiple departments, ambiguous unit, and no match. Assert the 99 Annex 2 rows are chair assignments rather than complete commissions.
4. Frontend integrates the new scope summary and paged detail views.
5. Verify: admin gets the full order; a department head gets only their authorized entries; a forged scope is rejected; changing scope invalidates job reuse; old job IDs preserve old results; cross-tenant/company reads fail; an ambiguous assignment is not auto-selected; pagination returns every item exactly once; normal AI errors identify the offending field.

The backend and AI developer plans must be reviewed together before enabling version 2 in production. The feature remains disabled until the AI service returns the agreed fixtures.
