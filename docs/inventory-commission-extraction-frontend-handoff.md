# Frontend handoff: inventory commission extraction

Date: 2026-11-12  
Status: backend extraction integration implemented locally; commission comparison and creation recommendation await the separate AI compare contract.

## Backend behavior

Starting a **new** inventory document analysis now sends `commissionAnalysisVersion: 1` in the existing multipart metadata. The backend stores the returned `commissionAnalysis` block with the analysis result and returns it unchanged at `data.result.commissionAnalysis` in the existing job response. Earlier saved analyses remain readable; to obtain the commission block from old files, start a new analysis with a new job/`analysisId`. A retry of the **same** job reuses its original version and ID.

The existing create form and AI job endpoints do not change. `sourceKey` identifies a **document commission in one analysis**, not an ERP commission ID. It must never be sent as `commissionId` in count creation.

## Frontend change required

`mm-front/src/features/physical-inventory/inventoryDocumentAi.ts` currently copies only the old `fields`, `documentFacts`, `warnings`, `resolvedIds` and `analyzedFileIds` into `InventoryAiResult`. It **drops `commissionAnalysis`**. Extend the parsed type and validation to retain the block, including `schemaVersion`, `suggestedScope`, `scopeMentions`, `commissions`, `recommendedSourceKeys`, members, roles and evidence.

In the create drawer, show the extracted commissions as a list or table with expandable members and source citations. Support at least 106 groups and 119 member entries for the supplied order without hiding or silently truncating entries. Show `rosterCompleteness=PARTIAL` or `UNKNOWN` prominently; they are review states, not extraction failures. `fields.commissionName=null` can coexist with a populated commission list. Do not auto-select a document commission or turn a `sourceKey` into an ERP ID. The user must still select the appropriate existing ERP commission through the current picker until comparison is integrated.

`recommendedSourceKeys` highlights document commissions relevant to the selected scope; it does not approve or select one. `suggestedScope.reviewRequired=true` means the user chooses the count scope. `ORG_UNIT` such as `008-sex` is a source label and needs ERP matching before being treated as a department. An optional `selectedScope` can later be included in AI metadata by the backend to improve recommendations; it is not required for this extraction release.

## Remaining AI dependency

The supplied `inventory-commission-mm-integration-handoff.md` explicitly places `POST /v1/inventory-commission-review/compare` in a later stage. Its referenced `inventory-commission-review-api.md` was not supplied. The comparison table, match percentages, resolved ERP user IDs and editable `CommissionCreateRequest` draft cannot be wired safely from this extraction response alone. Once the compare contract and deployed endpoint arrive, the backend will send authorized current-company commission/person candidates to AI, validate the result, and return the reviewed create draft. The frontend then edits the draft and submits the ordinary `POST /api/commissions` request.

## Verification performed

- Multipart metadata is a text part without filename and contains `commissionAnalysisVersion: 1` for new jobs.
- A versioned response without `commissionAnalysis` or with invalid source file IDs is rejected before storage.
- A 106-commission response passes validation without the former 64 KiB cap truncating it.
- The new block survives backend validation and resolution unchanged.
