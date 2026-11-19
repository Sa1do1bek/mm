# Inventory creation: document upload and AI analysis frontend handoff

Date: 2026-11-10
Backend status: aligned with the AI developer's implemented `inventory-document-extraction-api.md` contract. Production deployment and live ERP-to-AI connectivity are still unverified. Frontend code was not changed.

## User flow

At the top of **New inventory**, let the user add several documents and press **AI orqali tahlil**. The ERP API stages each file, runs an asynchronous AI job and returns editable suggestions. The user checks the suggestions and creates the inventory. Creation binds the staged source documents to that inventory. Analysis alone creates no inventory and changes no stock.

Use the existing authenticated ERP API client and current tenant/company headers. Successful calls use the normal `{data, message, errorCode, timestamp}` envelope; read `response.data.data` once. AI work uses the existing `AiAnalysisJobResponse` status values `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`. The upload/analysis routes require `ADMIN`, `MM_MANAGER`, `WAREHOUSE_MANAGER` or `WAREHOUSE_HEAD` and the draft owner.

## ERP API

| Step | Request | Response `data` |
| --- | --- | --- |
| Create a private draft | `POST /api/inventory/ai-drafts` | `{id, expiresAt}` |
| Add a document | `POST /api/inventory/ai-drafts/{id}/files`, multipart part `file` | `AttachmentDto` including `id`, `originalFileName`, `contentType`, `sizeBytes` |
| Show staged documents | `GET /api/inventory/ai-drafts/{id}/files` | Array of `AttachmentDto` |
| Remove a document | `DELETE /api/inventory/ai-drafts/{id}/files/{fileId}` | Normal success envelope |
| Start AI analysis | `POST /api/inventory/ai-drafts/{id}/analysis-jobs`, optional `Accept-Language: uz`, `uz-Latn`, `uz-Cyrl` or `ru` | `202`, `AiAnalysisJobResponse` |
| Poll | `GET /api/ai-analysis-jobs/{jobId}` | `AiAnalysisJobResponse`; inspect `status`, `result`, `errorCode`, `errorMessage` |
| Find latest draft job, if needed after refresh | `GET /api/ai-analysis-jobs/latest?analysisType=INVENTORY_DOCUMENT_EXTRACTION&entityType=INVENTORY_AI_DRAFT&entityId={id}` | Latest job or `NOT_REQUESTED` |
| Create reviewed inventory | Existing `POST /api/inventory`, with optional `sourceAiDraftId` in JSON and `Idempotency-Key` header | Existing `InventoryDocumentResponse` |

Each file is uploaded with a separate request. Accepted types: PDF, DOCX, PNG, JPEG, WebP. The backend checks the claimed MIME type against the file signature, rejects duplicate content, allows at most **10 files** and **50 MiB total** per draft, and uses the existing **20 MiB per-file** attachment limit. The current multipart request limit is 20 MB per request in local/production configuration; one file per request fits that model. A draft expires after **24 hours** unless it has been bound to an inventory. Upload/remove/bind is unavailable while an analysis job is active.

Inventory extraction defaults to Uzbek when no language header is sent. The ERP sends the AI service the ordered files, IDs, names, MIME types and SHA-256 hashes. The ERP uploads once to the AI service's asynchronous `POST /v1/inventory-document-extraction/jobs` route and polls the returned `statusUrl` until completion. The upload timeout is 350 seconds; the overall polling deadline defaults to 30 minutes (`AI_INVENTORY_EXTRACTION_JOB_TIMEOUT`). The ERP sends tenant and company headers on both upload and poll requests and preserves the `analysisVersion=2` query parameter for scoped analyses.

The draft file IDs can be downloaded through the existing authenticated `GET /api/attachments/{fileId}/download` route while the owner still has an open draft. After creation, the files are attached to `INVENTORY_DOCUMENT` with attachment type `SOURCE_DOCUMENT`; use the normal inventory attachment listing thereafter.

## `COMPLETED` result

`result` is the validated AI JSON plus `resolvedIds`. Every `fields` member is `null` or `{value, confidence, sources:[{fileId,page,quote}]}`. The fields are:

`name`, `inventoryType`, `globalWarehouse`, `warehouseName`, `commissionName`, `storageLocationName`, `storageBinName`, `plannedStartDate`, `plannedEndDate`, `description`.

Additional `documentFacts` may contain `orderNumber`, `orderDate` and `stockAsOfDate`. These are for display/review, **not** planned date fields. `warnings` explains ambiguous or unmatched values. `resolvedIds` may contain `warehouseId`, `commissionId`, `storageLocationId` and `storageBinId` only when a unique active record in the current authorized scope matches the suggested name or code. Missing IDs mean the user must select the reference manually. No material/material-group IDs are inferred in this release; the user chooses them through the existing form if needed.

`analyzedFileIds` lists the exact staged files used for this result. Compare it with the current draft file IDs after an upload or removal. If the sets differ, mark the old result stale and require a new analysis before applying suggestions.

The job status `COMPLETED` means the AI service returned a schema-valid result. The inner `result.status` may be `EXTRACTED`, `NEEDS_REVIEW` or `NO_EXTRACTABLE_CONTENT`. For `NO_EXTRACTABLE_CONTENT`, show the result and warnings without applying any form values.
For DOCX, source `page: 1` identifies the whole logical document; the AI service marks this for review. Show the quote and file name instead of treating it as a precise physical page citation.

Example mapping into the existing form:

| AI result | Form field |
| --- | --- |
| `fields.name.value` | `name` |
| `fields.inventoryType.value` | `inventoryType` |
| `fields.globalWarehouse.value` | `globalWarehouse`, only if non-null |
| `resolvedIds.warehouseId` | `warehouseId`, only if present and `globalWarehouse` is not true |
| `resolvedIds.commissionId` | `commissionId`, only if present |
| `resolvedIds.storageLocationId`, `resolvedIds.storageBinId` | Corresponding location/bin, only when parent IDs are present |
| `fields.plannedStartDate.value`, `fields.plannedEndDate.value` | Date inputs |
| `fields.description.value` | `description` |

Show confidence/evidence and warnings near the proposed fields, and leave all values editable. Do not submit `documentFacts`, `warnings`, `fields`, `resolvedIds` or AI job IDs in the create body. Send only the reviewed ordinary inventory fields plus `sourceAiDraftId`. Existing required field, date-order, warehouse and commission validation still applies. If the example order is analyzed, 1 October is the stock **as-of** date; the planned work dates are 20 October to 20 November. The central/local commission references in that order are ambiguous, so the user selects the intended ERP commission.

Use a stable random `Idempotency-Key` for the create attempt. After a lost response, retry the **same key and same reviewed body**. If the user changes the body after a validation failure, generate a new key. When `sourceAiDraftId` is present, the backend requires the header and binds source files in the same creation transaction. Do not start another creation just to retry a file attachment.

## Errors and release dependency

`FAILED` jobs expose `errorCode` and `errorMessage`; keep the draft and its selected files visible so the user can retry analysis. Retrying with the same files and language requeues the same ERP job and reuses its AI `analysisId`. A changed file set or language starts a new job. A repeated click on an active or completed identical job returns that job. A malformed AI response, unreadable source or upstream failure is an error, not empty autofill. The confirmed AI service route is `POST /v1/inventory-document-extraction/jobs` using multipart `metadata` and repeated `files` parts, followed by `GET` on the returned `statusUrl`. Enable the analysis button in an environment only after the compatible AI service is deployed and reachable.

## Backend verification in this workspace

- Backend main and test compilation passed.
- 50 selected tests passed after alignment with the AI contract, covering multipart AI requests, retry identity, language selection, result validation, AI jobs and attachment behavior. Earlier backend checks covered draft validation, reviewed inventory creation and file binding.
- `git diff --check` passed.
- Live MM AI service and production PostgreSQL migration were not exercised here. Confirm them together in staging before enabling the frontend action.
