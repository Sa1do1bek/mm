# Copilot run cancellation frontend handoff

Date: 2026-11-14

The ERP backend now supports durable cancellation of a Copilot run. The frontend still uses **Stop observing**, which only stops the browser from polling and leaves the run active. Add an explicit **Cancel answer** action that calls the backend before allowing another question in the same conversation.

## API contract

```http
POST /api/mm/ai/copilot/conversations/{conversationId}/runs/{runId}/cancel
Authorization: Bearer <ERP token>
X-Tenant-Id: <tenant>
X-Company-Id: <company>
Content-Type: application/json

{}
```

Read the existing MM response envelope once. A successful `data` value is `{ "runId": "<the requested runId>", "status": "CANCELLED" }`. Validate both fields. The route uses the same owner, tenant and company checks as conversation history and polling. A repeated cancellation returns the same successful result. If completion won the race, the route returns HTTP 409; refresh the run and history to show the actual terminal result. A 404 means the conversation is unavailable to the caller. On a connection failure, keep the pending run and offer a retry with the same IDs.

The backend also accepts cancellation before the first submit is stored. It reserves that run ID as `CANCELLED`; a late submit with the same ID returns `CANCELLED` without starting AI work. The frontend should therefore call cancel for the current run ID even when its initial submission has not yet received a response.

## Frontend behavior

1. Put **Cancel answer** on the active run, including when observation is paused or has timed out. Keep it distinct from merely stopping observation. Disable repeat clicks while its request is in flight.
2. Stop local polling for that run, call the cancel route with the current conversation and run IDs, and wait for `CANCELLED` acknowledgment. Only then clear the local pending run, unsent snapshot and manual resume marker. Reload messages and pending runs from ERP before enabling the composer. Ignore any late result from the stopped observer.
3. Treat a `CANCELLED` response from submit or poll as terminal without adding an optimistic assistant answer. A cancelled user question may remain in server history with `CANCELLED` status; do not show cards, actions or answer text for that run.
4. Exclude `CANCELLED` rows when assembling browser history for a later question. The backend independently replaces client history with canonical non-cancelled server messages, so stale tabs cannot reintroduce the cancelled exchange.
5. If the cancel request fails, retain the pending run and allow retry or resume. Do not unlock a new question based only on aborting a browser request. A 409 should reconcile server state rather than retry cancellation indefinitely.

## Checks before release

- Cancel a `QUEUED` and a `RUNNING` run, then send a new question in the same conversation immediately after acknowledgment.
- Delay the original submit or poll response until after cancellation. It must not add an answer, card or action to the widget.
- Reload the chat and open it in a second tab or device. Both should read the same cancelled state, with no answer for that run.
- Send a later question with stale browser history containing the cancelled exchange. Its answer should use only non-cancelled context.
- Retry cancellation after a lost response, test cancellation before initial submission is stored, and test a 409 when completion wins.
- Confirm the route remains scoped to the authenticated conversation owner, tenant and company.

The external AI service has no verified cancellation operation in this workspace. ERP suppresses any late result, but upstream computation may continue. Stopping that work and confirming its own stored context excludes the cancelled exchange require a separate AI service change and a joint staging check.
