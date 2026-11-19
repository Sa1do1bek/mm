# Copilot rejection recovery and remaining frontend work

Date: 2026-11-04. Applies to the ERP integration in `COPILOT_POLLING_INTEGRATION.md` and the frontend main implementation reviewed at `10f6797d7`.

## Backend correction and response contract

Previously, ERP saved a QUEUED run before contacting AI, but an upstream HTTP 400/422 escaped as an HTTP error without finalizing that run. Corrected questions then encountered the existing active run and were blocked.

After this fix, an upstream AI HTTP 400 or 422 becomes a persisted terminal FAILED run. Both initial submission and saved-request polling return HTTP 200 using the existing ERP response wrapper:

```json
{
  "data": {
    "runId": "agent_original_id",
    "status": "FAILED",
    "answer": "The assistant could not process this question. Please revise it and try again.",
    "cards": [],
    "actions": [],
    "warnings": ["COPILOT_REQUEST_REJECTED"]
  },
  "message": null,
  "errorCode": null,
  "timestamp": "..."
}
```

The answer follows the saved request's Uzbek, Russian or English locale. Raw upstream errors, codes and correlation IDs are stored in evidence diagnostics, subject to the existing backend authorization policy. Ordinary responses and ordinary history reads do not expose them. HTTP 200 means a valid run response was delivered; `status: FAILED` remains a failed result.

The backend updates the saved user message to FAILED, appends one assistant message, removes the run from `pending-runs`, and caches the terminal response. Replaying the original request or polling the same run does not contact AI again or duplicate messages. A corrected question uses a new run ID in the same conversation. A concurrent late rejection cannot replace an already saved terminal answer.

The current frontend's normal terminal-result path already understands this response. It must preserve the explanation, reconcile the failed pair with server UUIDs, unlock sending after FAILED, and retain that pair when reopening history. Do not display this as a network error or a successful calculation. `COPILOT_REQUEST_REJECTED` is a technical marker, not a user-facing warning block.

Existing ERP runs stranded by the old implementation can be resumed with their saved request/ID. A repeated definitive upstream 400/422 now finalizes them; if AI can process the original request after its problem is resolved, normal completion is still allowed. There is no bulk migration or blanket failure of active runs.

Only upstream AI 400/422 are converted. ERP validation failures before admission continue to return HTTP 400 and save no run/message. Upstream 401/403/409 and uncertain 503/504/transport failures still leave the original saved run pending. The upstream-service-key 401 mapping remains HTTP 502 with `MM_AI_UPSTREAM_UNAUTHORIZED`; do not automatically retry it. A failed terminal-save transaction must not be interpreted as a delivered terminal answer: preserve the ID and resume after the storage error.

## Remaining frontend corrections

### 1. Prevent automatic authorization retries when opening a chat

Files: `src/features/copilot/CopilotSession.ts`, especially `select()` and `observe()`; `src/features/copilot/pollCopilot.ts`.

The immediate retry predicate excludes 401/403, but the session only records a manual-resume marker for `MM_AI_UPSTREAM_UNAUTHORIZED`. Reopening/focusing a chat with a saved run automatically calls `observe()` again, repeating an earlier 403 without pressing Resume. This was reproduced independently of the existing test suite.

Persist the manual-resume gate for authorization failures and honor it during open, focus, reload, selection and recovery of an unacknowledged submission. Keep the original request and run ID. Do not discard the pending run or unlock a new computation merely because observation was denied. Preserve the application's normal 401/logout flow and clear private state when the authenticated user, tenant or company changes. Explicit Resume can try the same run after access is restored; no new run ID is needed.

Add tests for an initial 403 with an ERP-saved pending run, a 403 from a saved-request poll, focus/reopen, full reload, two tabs, explicit Resume and scope changes. Opening/focusing/reloading must issue no new AI submit/poll after authorization failure in that tab unless the user explicitly resumes under the intended policy. History/list requests may still run and enforce their own access checks.

### 2. Verify and implement existing card-type rendering

File: `src/components/AiCopilotWidget.tsx`, `CopilotResponseView()` and `renderDetailValue()`.

Current cards use a generic shell, metrics and nested detail lists. Markdown tables are supported separately. Obtain representative real AI payloads for the existing table and navigation card types; their precise schemas are not supplied by the integration document, so do not invent new fields/types to satisfy the tests. Render actual table rows/columns with escaped cells and retain dates, units, limitations and pagination/truncation notes. Use the existing approved navigation flow for existing navigation cards and validate safe destinations/action types. Keep cards optional and never imply a suggestion executed an action.

Add tests using real contract fixtures for table, metric, navigation and empty-card responses, including restored history. No arbitrary SQL/action execution or mandatory new navigation buttons are required. If an action remains unconnected, retain the explicit suggestion-only behavior.

### 3. Align retry behavior with the supplied client

File: `src/features/copilot/pollCopilot.ts`.

The current predicate retries HTTP 408/429/500 in addition to 502/503/504, and its three-retry budget is cumulative across progress responses. The supplied implementation retries the documented transport errors and 502/503/504, resetting consecutive failures after a valid response and applying the documented retry delay. Align these behaviors unless a broader policy is explicitly agreed upon. Never retry `MM_AI_UPSTREAM_UNAUTHORIZED`, 401/403/409, validation failures or malformed response contracts automatically.

Retain the 1.5-second normal poll interval, 15-second request timeout, ten-minute observation limit, immutable snapshot and original run ID. Test intermittent failures separated by successful RUNNING responses, four consecutive failures, business/auth errors and a total observation timeout. Stopping observation must never claim server cancellation.

### 4. Reconcile uncertain admission with server state

File: `src/features/copilot/CopilotSession.ts`, the initial 400/422 rejection branch.

For the new backend contract, admitted upstream rejections arrive as HTTP 200/FAILED and use the existing terminal path. Retain a test for that exact distinction. Treat HTTP status alone cautiously when determining whether an earlier attempt was saved: a network/storage failure can leave admission uncertain, and another tab can have advanced the run. Reconcile history and `pending-runs` with the original ID instead of inventing a replacement computation. Local pre-admission validation errors may remain request errors, and must not create optimistic duplicates on correction.

## Joint acceptance checks

- Initial AI HTTP 400/422: one FAILED user/assistant pair, no pending run, clear localized answer, corrected question succeeds with a new run ID. Refresh preserves the failed pair without replaying it.
- Old pending run followed by AI HTTP 400/422: saved-request polling returns terminal FAILED once and unlocks the conversation. Duplicate polls and two tabs do not duplicate history.
- HTTP 401/403 or upstream service-key authentication failure: normal permission/session presentation; no automatic retry on focus/reopen/reload; explicit Resume retains the ID.
- Transient AI/storage outage: bounded retries, no fake terminal answer, original request/ID retained and resumable after restoration.
- Existing table/metric/navigation cards retain their business content and dates/units across reload. Raw HTML and executable links remain disabled.
- A real calculation over 60 seconds through authenticated staging ERP produces multiple short polls and one terminal answer. Test refresh/disconnect, two tabs, owner/company isolation and actual AI storage failure. Unit and browser tests with mocked boundaries do not establish this live result.

## Backend regression coverage

`CopilotChatServiceTest` covers both upstream 400 and 422 on initial send and resume; idempotent replay after service reconstruction; one failed pair and corrected-question admission; ordinary/privileged diagnostic visibility; authorization/conflict/transport errors remaining resumable; HTTP 200/FAILED versus ERP validation HTTP 400; and a late concurrent rejection preserving an existing completed answer. Tests use the V64 migration on isolated H2/Hikari storage with a mocked AI boundary. The existing client/security/error-handler/multilingual regression selection is rerun before publication.

This handoff supersedes the remaining-work assessment for these error paths in the earlier verification report. The original `docs/copilot-backend-frontend-handoff.md` remains the reference for all routes and the initial integration; the frontend main already implements its basic polling/history/Markdown flows.
