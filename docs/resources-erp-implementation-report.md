# Resources: ERP implementation status

Date: 2026-11-12

## Delivered in this change

- Flyway `V75` adds company-scoped resources, immutable file versions and a company knowledge revision counter.
- `POST /api/resources` accepts one multipart file per request with optional title and required `Idempotency-Key`. The browser sends multiple files as independent requests, up to two at once. A blank title falls back to the filename stem.
- A file is published in the same transaction after extension/content checks. Malware scanning is optional and currently disabled in production. When enabled, a clean ClamAV result is required and scanner failure blocks publication. PDF, DOCX, TXT and MD are accepted up to the attachment size limit (20 MiB by default).
- Company members can list/search titles and filenames, see versions, and preview/download safe originals. The owner or an admin can replace a version or archive a resource. Generic attachment upload/delete cannot bypass the Resources policy.
- The existing Copilot request now accepts `knowledgeScope` (`AUTO`, `RESOURCES`, `RESOURCE`). For `RESOURCE`, ERP checks company, publication and `READY` status, then adds the server-side knowledge revision. The outbound Copilot request includes service tenant/company headers. ERP rechecks cited resource/version IDs before exposing citation links. Old operational Copilot requests remain valid.
- Frontend has a global Resources entry, catalog/search, multi-file upload with editable titles and per-file results, version list, authenticated PDF preview/download, and an entry into the existing Copilot panel. Citations render as links to ERP-scoped preview routes.

## Deployment requirements

- The production pipeline runs the backend on its original Docker network and explicitly sets `RESOURCE_MALWARE_SCAN_ENABLED=false`. Uploads still enforce file size, extension and content checks, but are published without a malware scan. Review this risk before accepting uploads from untrusted users.
- To enable scanning later, set `RESOURCE_MALWARE_SCAN_ENABLED=true`, `RESOURCE_CLAMAV_HOST` to a reachable ClamAV daemon, and optionally `RESOURCE_CLAMAV_PORT` (default `3310`). When enabled, an unavailable scanner returns `503 RESOURCE_SCANNER_UNAVAILABLE` and prevents publication.
- Apply `V75` before deploying the frontend. Existing Resources calls require the authenticated tenant/company context and a valid token.
- Defaults: `app.resources.max-files-per-user=1000` and `app.resources.max-company-bytes=5368709120` (5 GiB). These application checks limit usage, but concurrent uploads can race; tune them along with the existing request rate limit and storage capacity.

## Resource knowledge integration

The ERP now uses the final resource-knowledge service contract. Flyway `V85` adds a durable, tenant and company-scoped delivery queue. A document upload, replacement, archive, or retry writes the relevant index and visibility events in the same ERP transaction.

- Index uploads reuse one immutable job ID. The worker sends the original bytes and immutable metadata, then polls the job until it is `READY` or `FAILED`. Transport failures retry with the same job ID. A restart or another ERP replica can resume a leased event safely.
- Visibility events publish `CURRENT`, `HISTORICAL`, or `BLOCKED` for every affected version. A version becomes `READY` in ERP only after its matching index and visibility events complete.
- Existing pending documents join the queue when `V85` is applied. Owners and administrators can retry a failed version from the Resources screen.
- Resource questions carry the server-owned knowledge revision. ERP accepts only citations for an authorized, ready resource version and builds its own preview URL. A response whose document revision changed is withheld and asks the user to submit the question again.
- Resources enforce the shared 20 MiB maximum. PDFs are limited to 30 pages and text documents to 60,000 characters. The default indexing language is Uzbek and can be set with `RESOURCE_DEFAULT_LANGUAGE`.

## Deployment and acceptance

Set `AI_BASE_URL` and `AI_API_KEY` for the resource AI service. The worker uses the same service configuration as Copilot and runs while `app.scheduling.enabled` remains enabled. `RESOURCE_KNOWLEDGE_WORKER_INTERVAL_MS` defaults to 1000 milliseconds.

After deploying the backend migration and frontend, upload a harmless document in staging, wait for the Resources status to become `AI tayyor`, then ask a question whose answer appears in the document. Confirm that the returned citation opens the authorized ERP preview route. A deployed document and service credentials were not available from this workspace, so this final staging acceptance remains to be performed.

The broader immutable operation-basis feature is outside this Resources slice, as recorded in [resources-erp-frontend-implementation-plan.md](resources-erp-frontend-implementation-plan.md).
