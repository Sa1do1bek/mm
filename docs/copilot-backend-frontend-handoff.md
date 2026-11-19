# Copilot polling, history and response UI handoff

Date: 2026-11-04

## Cancellation update (2026-11-14)

`POST /api/mm/ai/copilot/conversations/{id}/runs/{runId}/cancel` accepts an empty JSON body and returns the normal API envelope containing `{ "runId": "...", "status": "CANCELLED" }`. It requires the same authenticated owner, tenant and company scope as the other conversation routes. A repeated cancellation returns the same terminal status. A completed, failed or review-needed run returns 409. A cancellation that arrives before the initial submission creates a terminal run-ID tombstone, so a late submission with that ID returns `CANCELLED` and does not contact AI.

Cancellation immediately removes the run from pending runs and releases ERP's one-active-run gate. Polling returns `CANCELLED`; late upstream results cannot replace it. The cancelled user question may remain in message history with `CANCELLED` status, while no normal assistant answer for that run is returned. New run history is assembled from non-cancelled ERP messages, ignoring stale client-supplied history. The frontend integration remains to be done; see [the cancellation frontend handoff](copilot-run-cancellation-frontend-handoff.md).

The external AI service is a separate deployment and has no verified cancel operation in this workspace. Its computation may continue after ERP cancellation. ERP discards its late result; interrupting upstream execution and confirming that upstream memory does not include the cancelled exchange require a compatible AI service cancellation contract and a joint staging check.

Follow-up: frontend main now includes the initial polling/history UI. See [rejection recovery and remaining frontend work](copilot-rejection-fix-frontend-handoff.md) for the backend HTTP 400/422 fix and the remaining authorization, card-rendering, retry and staging checks. The initial delivery notes below describe the original handoff.

Reference: `COPILOT_POLLING_INTEGRATION.md` supplied on 2026-11-04. This delivery implements the ERP backend portion. The AI service described in that document is a separate deployment. Frontend work below remains required before the live chat meets the complete document.

## Backend delivery

- Flyway `V64__copilot_conversations.sql` creates ERP conversations, immutable run requests and ordered user/assistant messages. It does not create or modify the AI service's job tables.
- Every conversation operation checks the authenticated ERP user UUID, tenant and current company. IDs supplied by the browser do not confer access. Even global administrators cannot read someone else's chat through these routes.
- Conversations are created on the server. Unknown/browser-created conversation IDs return 404; do not adopt old browser IDs automatically.
- Before an AI call, the backend commits the run request and one user message. The same run ID resumes that request after a lost response, service restart, refresh or another tab. A different question/context/history/locale/execution mode/maxSteps under that ID returns 409. `includeEvidence` may vary.
- A conversation row lock serializes run admission and answer persistence across ERP replicas. Only one active run per conversation is accepted. Network calls hold no database locks. Concurrent polls can reach AI with the same ID; AI owns execution deduplication. A terminal result cannot be overwritten by a late progress response.
- COMPLETED, FAILED, CANCELLED and NEEDS_REVIEW responses are persisted once per run/role with the complete answer, cards, actions, warnings and diagnostic payload. Terminal repeats are served from ERP storage. Transport failures leave the saved run pending so it can be resumed.
- QUEUED/RUNNING updates the existing user-message status and pending run; it does not append an assistant message. Timestamp-based ordering is not used: messages have stable UUIDs and sequence numbers.
- `answer`, cards, actions, warnings and business status pass through without rewriting. Ordinary users receive no top-level `plan`, `evidence`, `agentsUsed`, `llmUsed` or `durationMs`; global administrators can inspect those fields for their own chats. `runId` remains necessary transport metadata. The full response stays in restricted ERP storage.
- Copilot routes bypass generic multilingual/name-display transformation helpers so request snapshots, saved history and AI payloads retain their original content. The existing authenticated route and service ownership checks still apply.
- The service key remains inside `HttpMmAiClient`. Saved requests allow only the documented request fields, supported screen/company/path context and bounded text history; do not send credentials in these fields. Company context is checked and set from the authenticated ERP scope.
- AI HTTP statuses are retained, except upstream 401 remains HTTP 502 to avoid logging the ERP user out for an invalid service key. It now carries `errorCode: MM_AI_UPSTREAM_UNAUTHORIZED`, which must never be retried automatically. ERP 401/403 still use the existing session/permission flow. AI connection failures become 503 / `COPILOT_TRANSPORT_ERROR`; ERP chat-storage failures become 503 / `COPILOT_STORAGE_UNAVAILABLE`. No synchronous fallback is introduced.
- Omitted `executionMode` preserves the AI synchronous mode; a missing run ID in that legacy mode is generated by ERP. New clients must explicitly use polling with a stable client-generated run ID. Both modes require a server-created, owned conversation.

## API contract

All routes use the existing ERP bearer token, tenant and company headers. Base path: `/api/mm/ai/copilot`. Successful responses use the existing `{data, message, errorCode, timestamp}` wrapper. Axios callers read `response.data.data` once, or use the supplied decoder once.

| Method | Path | Request / result |
|---|---|---|
| POST | `/conversations` | Optional `{ "title": "New chat" }`; returns conversation |
| GET | `/conversations?archived=false&page=0&size=20` | Owner/company-scoped page, newest updated first |
| GET | `/conversations/{id}` | Conversation metadata |
| PATCH | `/conversations/{id}` | `{ "title": "...", "archived": true }`; either field optional; `false` restores |
| GET | `/conversations/{id}/messages?page=0&size=50` | Message page in ascending sequence order |
| GET | `/conversations/{id}/pending-runs` | Array of `{runId,status,request}`; request is the saved snapshot |
| POST | `/messages` | Documented AI request with owned `conversationId`, `executionMode:"poll"` and stable `runId`; returns run response |
| POST | `/conversations/{id}/runs/{runId}/poll` | Empty body; resumes the backend-saved request, or returns cached terminal response |

Conversation shape: `{id,title,archived,createdAt,updatedAt}`. Message shape: `{id,sequence,role,content,runId,status,response,createdAt}`. `response` is null for user messages; the assistant response contains cards/actions/warnings. Pages contain `{content,page,size,totalElements}`. Page numbers start at zero; size is 1–100. Titles must contain 1–200 characters. New messages are blocked in archived conversations; existing pending runs can still be polled. Archiving preserves history and does not cancel a run.

Supported request fields: `message` (1–20000 characters), `locale`, `conversationId`, `context`, `history`, `maxSteps`, `includeEvidence`, `executionMode`, `runId`. Context supports `screen`, `companyId`, `path`. History supports at most 20 `{role:"user"|"assistant",content:string}` entries, each content at most 4000 characters; no cards, SQL, evidence or system messages. Run IDs use 1–100 ASCII letters, digits, `_` or `-`; prefer the document's 128-bit random `agent_...` IDs.

## Frontend implementation required

1. Replace the in-memory-only identity in `mm-front/src/components/AiCopilotWidget.tsx` with POST `/conversations` for a new chat. Load conversation lists and paged messages from ERP storage. Keep the same ID on refresh and follow-up questions. Add rename, archive/restore and new-chat controls. Use message UUIDs as React keys, and retain `response` when reconstructing history.
2. Replace the single POST in `sendMessage` with the document's `pollCopilot` helper. Its `post` callback must return the already decoded AI run object. Send the first request to `/messages` with `executionMode:"poll"`, one random run ID and an immutable snapshot. Subsequent polls may send that exact body to `/messages` or use the empty-body saved-request poll route above. Keep the existing authenticated Axios client.
3. Use 1.5-second intervals, a 15-second request limit, up to three transient retries and a 10-minute observation limit as specified. Override the supplied retry predicate to return false when `error.response.data.errorCode === 'MM_AI_UPSTREAM_UNAUTHORIZED'`, even though its HTTP status is 502. Do not retry 401/403/409 or request/business errors automatically. Keep the original run ID after network errors, timeout or uncertain submission; offer Resume. If storage was unavailable on first submission, resubmit the original body and ID.
4. On opening/reloading a chat, load its history and pending runs. Resume the backend-saved run; do not reconstruct it from the currently selected screen or current draft. ERP stores it before contacting AI. Browser storage is optional convenience, never the main history store. Treat saved request bodies as confidential user data.
5. QUEUED/RUNNING shows one progress indicator (for Uzbek, “Tekshiryapman…”). Add/upsert one assistant response only at COMPLETED/FAILED/CANCELLED/NEEDS_REVIEW. Disable new submissions within that conversation while a run remains pending; a local observation timeout does not unlock a new server computation. Other conversations remain independent. Reconcile with server messages by run ID/role to avoid duplicate optimistic entries across tabs.
6. Abort observation and invalidate late callbacks on new-chat, account/company changes, logout and component disposal. Clear prior account/company chat data before fetching the new scope. A locally aborted request does not cancel accepted AI work. Switching back to a chat can resume it. Do not label observation cancellation as server cancellation.
7. Render `answer` as safe Markdown (bold, lists, links), with raw HTML disabled and executable link schemes rejected. Never put AI text in `innerHTML`. Card titles/labels/cells stay escaped. Preserve table/metric dates, units and limitations. Empty `cards` is valid; do not require navigation buttons on every response.
8. Remove the normal-response UUID, duration and COMPLETED badges currently displayed by `CopilotResponseView`. Use a separate, normally closed diagnostics view only when those fields are authorized and present. Do not produce one yellow warning block per raw technical code. Preserve human-readable freshness/completeness caveats in `answer`.
9. Interpret `ASSISTANT_OUTCOME:clarification` as a neutral question/options response even if V1 status is FAILED; `ASSISTANT_OUTCOME:partial` as partial with the server explanation; `ASSISTANT_OUTCOME:denied` as permission denial. Other FAILED responses show the existing server answer. Transport errors are connection problems, never “no data found.” These markers are in the response warnings; preserve the original status for history.
10. Existing action buttons currently display a “not connected yet” notice. Keep suggestions distinct from completed actions. Any action/navigation wiring must validate the allowed action type and safe target through the ERP's existing flow; do not execute model output directly. The source document does not require inventing new actions or navigation flows.

## Acceptance and rollout

Backend automated coverage uses the production migration on an isolated H2 database in PostgreSQL mode, service calls with real transactions, and a mocked AI boundary. It verifies saved-request recovery, owner/tenant/company isolation, idempotent terminal messages with cards, concurrent-tab terminal ordering, request validation, diagnostics policy, archive/rename and invalid upstream identities. HTTP client and error-handler tests cover field forwarding and the upstream-authentication distinction. This does not prove the external AI deployment's lease, heartbeat, checkpoint, memory or capacity behavior.

Verification on 2026-11-04: backend compilation succeeded. The selected regression run executed 56 tests: 54 passed, including all 12 copilot service/controller tests, and two failed in `UserIdentityDisplayResponseAdviceTest` (`replacesUserReferenceFieldsWithFullNamesAcrossNestedResponsePayloads` and `replacesRfqWorkspaceUserFieldsWithFullNames`). They expected resolved names but received usernames. Those tests call the unchanged `beforeBodyWrite` method directly; the copilot change only adjusts that advice's `supports` method to exclude `CopilotController`. No fix to the separate name-resolution behavior was included. The complete application test suite and a live PostgreSQL/AI integration were not run.

After integrating `main` at `71b283ec`, the copilot migration was renumbered to V64 because main already contains V63. The 53-test copilot/client/error-handler/multilingual regression selection passed on the merged code. The test fixture now uses a production-style Hikari connection pool to accommodate H2 2.4.240's session-bound CHECK-IN constraint handling. Duplicate-key conflicts alone are translated to run-identity conflicts; other storage-integrity errors retain their storage-failure handling. The two separate user-name display failures above remain recorded from the wider run.

Before enabling the new frontend, deploy the ERP migration/backend and the compatible external AI version to staging together. Existing frontend-generated conversation IDs will receive 404 until the handoff is implemented, so coordinate release or keep the old UI unavailable during the transition.

Required joint staging checks from the source document:

- A real calculation exceeding 60 seconds produces short POSTs using one run ID and ends in a concrete terminal status.
- Lost initial reply, disconnect, refresh, ERP restart and two tabs resume the original computation and produce one stored assistant answer.
- AI storage outage surfaces as 503 without sync fallback; invalid ERP sessions and permissions follow 401/403 flow; changed run inputs produce 409.
- User B cannot list/read/send/poll/rename/archive user A's conversations or run IDs; repeat under another tenant/company.
- Refresh and a later login restore full messages and cards, including more than five exchanges. New chats receive isolated conversation IDs; model memory remains only the recent context described by the AI service, not the complete ERP archive.
- Markdown, safe links, tables/metrics, clarification/partial/denied outcomes and diagnostic visibility meet the source UI checks.

The AI service's queue/lease/heartbeat/recovery implementation and production capacity must be verified in that service, not duplicated as a second ERP worker queue. No independent ERP polling daemon was added: after a restart, frontend polling resumes pending work. No proxy timeout, CI/CD, Nginx or Docker changes are required by this patch. Full deletion is not implemented by archive; retention, backups and coordinated ERP/AI erasure remain an operational policy requiring agreement as stated in the source document. Persisted full responses and questions must be included in that policy.
