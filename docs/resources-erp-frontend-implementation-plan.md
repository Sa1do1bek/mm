# Resources library and Copilot: ERP backend and frontend plan

Date: 2026-11-12

Status: implementation plan; no Resources code is claimed as complete

Owners: ERP backend and frontend (our work)

AI service handoff: [resources-ai-service-handoff-uz.md](resources-ai-service-handoff-uz.md)

## 1. Decisions from the user

- Every authenticated user can upload **one or multiple** resource files in the current tenant/company. Each selected file creates a separate resource with its own title, version and processing status. A batch is a frontend convenience, not a combined document.
- A manually entered title wins. If blank, the server derives a title from the safe file basename (without extension). A final nonblank title is required. The UI pre-fills from the filename and lets the user edit it; the backend repeats the fallback/validation. Duplicate titles are allowed and disambiguated when asking Copilot.
- There is **no reviewer approval step for now**. A file becomes visible to every authenticated user in the same company as soon as required file safety checks pass. A rejected/unsafe file never becomes public. AI OCR/indexing is asynchronous and does not hold back document viewing or download; Copilot can use only `READY` versions.
- All company users can read published resources and ask Copilot about them, regardless of role, department or assigned warehouse. Company/tenant isolation remains mandatory. Upload and chat still require authentication. An uploader may manage their own uploads; administrative remediation has a separate authorization check.
- The first delivery covers the Resources page, batch upload, safe publication, versions, catalog/preview/download, document-specific and named-document Copilot questions with citations. The original broader plan's immutable operation-basis feature is a later delivery and is **not** silently treated as implemented by this slice.

## 2. Verified starting point

- Backend: `AttachmentController`/`AttachmentService` store single attachments under an entity access policy. That generic endpoint must **not** be opened for every ERP attachment. Add a scoped resource path/entity policy.
- Backend: `CopilotController` uses `/api/mm/ai/copilot`; `CopilotChatService` owns conversations by user, tenant and company, accepts a fixed request-field allowlist and persists/polls `runId` results. `HttpMmAiClient` calls `/v1/copilot/messages`. Extend this path rather than creating a second chat system.
- Frontend: `src/features/copilot/api.ts`, `CopilotSession.ts` and types validate and retain request snapshots; every new request field and response citation needs coordinated updates. There is no Resources feature in the inspected frontend checkout.
- AI: permanent document indexing, resource retrieval and citations are **proposed**, not proven by the current inventory-extraction endpoint. Do not treat the inventory order AI response as a library index.

## 3. Backend implementation order

### B1. Schema, storage and access

Create Flyway migrations for `resources`, `resource_versions`, processing/outbox jobs and optional categories/tags. The version owns an immutable attachment ID, SHA-256, uploader, title snapshot, content type, size, lifecycle state, index generation and timestamps. Resource has tenant/company, current version pointer, display title, owner/uploader, soft-delete/archive markers and `knowledgeRevision` or a company-scoped revision source. Choose the next free migration version at implementation time.

Use private attachment storage and a `RESOURCE_VERSION`-specific access policy. A signed-in user in the same tenant/company may preview/download versions that passed safety checks; draft/rejected/quarantined bytes remain private to the uploader and authorized administrators. Do not weaken access to other attachment entity types. Direct ID access, list/search, citations and downloads all check tenant/company server-side. Browser headers or IDs never override authenticated context.

Before auto-publication, enforce content type/signature and size/page limits, safe parser behavior and the required malware/safety check. Inspect available scanner infrastructure; absence of a required scanner must not silently count as a pass. Start with PDF, scanned PDF, DOCX, TXT and MD. Unsupported, encrypted or unsafe files receive a per-file reason. Apply upload rate/storage quotas to every user. Store checksum and audit events. Never log raw file contents or service keys.

### B2. Upload, title and lifecycle

Prefer one `POST /api/resources` multipart request per file (`file`, optional `title`, optional category/language, `Idempotency-Key`). The frontend can issue several with bounded concurrency and show an independent result for each. This avoids an all-or-nothing batch and lets one failed file be retried without duplicating successful files. Server returns `{resourceId,versionId,title,visibilityStatus,indexStatus}`. File/version ID and title are stable after creation; an edited display title is versioned or recorded as metadata history.

Title rule: trim the user's text; if empty, take a sanitized filename stem; reject if that is also empty; enforce length and safe characters. The uploader cannot impersonate another company by submitting IDs. An identical idempotency key with different content/title returns `409`. Validate bytes before accepting a successful publication. If scanning is asynchronous, initial status is `PROCESSING` and list/download excludes it; after a clean scan, move to `PUBLISHED` atomically and emit an index job. Publish requires no reviewer action. If the index later fails, keep `PUBLISHED` with `AI_FAILED` and a retry path.

For a replacement, create version N+1; keep N as historical. Atomic current pointer and title snapshot changes prevent a late index job from becoming current. Uploader may edit/replace/archive their own resource subject to safety and audit checks; company admin may remediate. Historical versions remain accessible when safe. Quarantine blocks download and AI even if previously published. Physical deletion must respect future historical references.

### B3. Catalog and endpoints

Implement the following **proposed** ERP endpoints using existing response envelopes and pagination conventions. Final DTO/OpenAPI are fixed before frontend coding:

| Method | Path | Behavior |
|---|---|---|
| POST | `/api/resources` | Single file + title; batch UX calls it once per file |
| GET | `/api/resources` | Company catalog, title/filename search, filters, pagination; only safe published entries |
| GET | `/api/resources/{id}` | Card, title, owner, versions, safety/index state |
| GET | `/api/resources/{id}/versions` | Current and safe historical published versions |
| GET | `/api/resources/{id}/versions/{versionId}/preview` | Scoped, safe original/preview |
| GET | `/api/resources/{id}/versions/{versionId}/download` | Scoped original |
| GET | `/api/resources/{id}/processing` | Upload safety and AI indexing status |
| PATCH | `/api/resources/{id}` | Owner/admin metadata edit with audit/version rules |
| POST | `/api/resources/{id}/versions` | Owner/admin replacement file |
| POST | `/api/resources/{id}/archive` | Owner/admin, current eligibility off; historical remains |
| GET | `/api/resources/citations/{citationId}` | Validated source and locator, same company |

At minimum, title search is available from ERP storage when AI is down. Full text search needs an indexed text representation and should be added after the AI extraction contract is verified; do not present filename-only search as document-content search. Catalog listing and original download must remain available when the AI service is unavailable.

### B4. Indexing integration

After safe publication, an outbox job sends the immutable version file and metadata to the AI index endpoint in the Uzbek handoff. Do not hold a database transaction across the AI HTTP call. Poll its job status and record `QUEUED/RUNNING/READY/FAILED` per version. Retry transport failures with the same job identity and bytes; the AI service deduplicates. Reindex increments generation. Company `knowledgeRevision` increments on publish, archive, quarantine, current version change and metadata changes relevant to title search.

Send visibility changes to AI when a version becomes historical or blocked. A failed visibility sync must not let the ERP return stale citations: ERP checks citation resource/version status and company before exposing the answer/link. For serious desync, suppress resource answers until reconciled. A title edit must also update AI title search via metadata sync or new index generation; no stale-name lookup.

### B5. Existing Copilot extension

Add validated optional `knowledgeScope` to `CopilotChatService.REQUEST_FIELDS`, persisted request identity, and the `HttpMmAiClient` payload. `AUTO` (or absent) lets the AI route a named-document question in normal Copilot; `RESOURCES` searches the current company library; `RESOURCE` requires server-validated `resourceId` and optional `versionId` from the Resources page. The backend obtains tenant/company and `knowledgeRevision`; it does not trust browser-supplied values. The same `runId` with changed scope returns `409`; refresh/poll reuses the saved request. The current generic `HttpMmAiClient.post` sends the API key but does **not** add tenant/company headers; explicitly add those headers to the Copilot resource path and verify them in an HTTP client test.

Preserve the existing answer/cards/actions/warnings/status/runId behavior. Accept and save `answerMode` and `citations[]`; citations remain visible to ordinary users even when admin `evidence` is hidden or `includeEvidence=false`. Validate every cited version exists, is/was published safely in the same tenant/company, and the locator belongs to that version. Construct ERP preview links server-side. A draft, rejected or quarantined source is never cited. Keep conversation ownership and existing operational ERP permissions unchanged.

## 4. Frontend implementation order

### F1. Resources page

Add a global `Resources` navigation item for every authenticated user. Page: searchable/paginated catalog, upload entry, status filter, empty/loading/error states, and per-resource card with title, original filename, current version, language, file type, uploader/date, download/preview and AI readiness. Historical versions are visibly marked. No warehouse selection or reader-role filter.

### F2. Multiple-file upload

Allow selecting or dropping several supported files. Render **one row per file** with a title input initially filled from the filename stem, which the user can edit or clear. Before upload, the visible title resolves as manual text first, then filename fallback; each row must resolve to a nonblank title. Send one idempotent request per row with bounded concurrency. Show upload, safety processing, published, AI indexing/ready/failed/rejected separately. Partial success remains visible; retry only failed rows. Warn that a clean upload becomes readable by everyone in the current company after safety processing, without reviewer approval.

### F3. Card, viewer and Copilot entry

Open the original through scoped ERP preview/download. Show version history and the chosen/current version. `Ask about this document` opens the **existing** Copilot with `knowledgeScope.mode=RESOURCE` plus `resourceId` and optional version ID. If AI is not ready, explain that the file can still be read/downloaded. In ordinary Copilot, a user may type the document title; ambiguous titles produce selectable candidates instead of a guessed answer.

### F4. Citations and resilient chat

Extend `src/features/copilot/types.ts`, `api.ts` decoder/request snapshot, `CopilotSession.ts` persistence/polling and answer rendering for `knowledgeScope`, `answerMode` and `citations`. Render a cited title/version/section/page beside the relevant answer; click through the scoped ERP citation endpoint. Do not derive a URL from AI text. Keep normal chat, run replay, session switching and diagnostic visibility behavior. Use existing UZ/RU/EN localization and responsive/accessibility patterns.

## 5. Acceptance checks and handoff gates

1. A viewer, warehouse worker and admin in one company can each upload two files; a clean file from each is visible/readable to the others after safety processing. A different company/tenant cannot list, preview, download, search or cite it.
2. Manual title overrides filename; blank manual title falls back to filename; no usable title is rejected. Duplicate titles trigger document choice in chat. One bad file does not roll back a successful sibling upload.
3. Unsafe/unsupported/oversized file never becomes public. AI/index outage does not remove a clean published file from the library. Index status is honest and retryable.
4. Asking from a resource card limits retrieval to that resource; typing its name in ordinary Copilot finds it or asks for clarification. Answer citations open the exact safe version and locator. A nonexistent/foreign/blocked version ID is rejected.
5. Run retry, refresh and polling use the original scope; changing scope under the same `runId` conflicts. Existing Copilot operational questions, user-owned chat history and non-resource attachment access regressions pass.
6. Version 2 does not rewrite a version 1 citation; archive/quarantine changes current retrieval eligibility. Cross-company index/cache contamination and stale visibility events are tested.

## 6. Delivery sequence

1. Agree the AI contract in [resources-ai-service-handoff-uz.md](resources-ai-service-handoff-uz.md); obtain the AI team's final endpoint/DTO examples. This document is our proposed contract, not an implemented service.
2. Implement backend schema, scoped storage, all-user upload, safety/auto-publication and catalog/download. Exercise this without AI.
3. Implement frontend Resources catalog and multiple-file upload against those APIs.
4. Integrate AI indexing and status, then extend existing Copilot end to end for named-document and selected-document questions with validated citations.
5. Run tenant/company, upload, citation, version, recovery and existing Copilot regressions; deploy backend/migrations, compatible AI service and frontend flag in that order. Do not claim working AI Q&A until staging verifies a real indexed file.

Deferred from the original broad Resources plan: operation-specific immutable document basis and later document formats/media. These require separate posting-flow design and acceptance work after this first library/chat slice.
