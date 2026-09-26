# Customer asset acceptance and approved title correction

Acceptance, approved correction and current ownership reporting are implemented.
The task21 plan checkbox remains unchecked pending independent review.

## Acceptance command

`POST /api/customers/{customerId}/assets/handover` requires `Idempotency-Key` and
the strict JSON body:

```json
{
  "assignmentId": "existing-assignment-uuid",
  "expectedRevision": 0,
  "expectedTitleRevision": 0,
  "evidenceId": "committed-work-order-signature-revision-uuid"
}
```

The assigned active technician must retain current field/assignment permissions,
area scope and the warehouse scope of the original acknowledged source. The
customer path must match the verified assignment. Tenant, actor, customer title,
ownership intent, acceptance time and evidence digest are not client authority.
Unknown fields are rejected before replay lookup.

Evidence references reuse authenticated work-order signature storage. The command
locks its committed metadata and verifies the stored content digest. It does not
introduce an upload endpoint. The immutable acceptance snapshot retains the
assignment, authorization, physical position, source revisions, work-order code,
sender/customer/receiver labels, signature reference/digest, actor and timestamp.

LOAN remains ISP-owned and creates one assignment/asset/customer-linked recovery
obligation. SALE remains ISP-owned until acceptance. Acceptance then posts exactly
one outgoing ISP 1EA leg and one incoming CUSTOMER 1EA leg at the same installed
location and custody. The physical asset and customer installation are not
duplicated. Both remain unavailable to warehouse issue. No invoice, payment or
refund is created.

Same-key, same-payload replay returns the original assignment response after
current authority and source scope checks. A different payload or business key
cannot accept the assignment a second time. Direct mode/title/history edits are
not a correction path.

## Independently approved correction

`POST /api/v1/warehouse/asset-title-corrections`, with `Idempotency-Key`, creates a
new immutable correction request. It requires current approval request/view
permission and warehouse/area access. Obtain the current revisions from the
ownership endpoint; do not reuse the installation response as current title.

```json
{
  "assignmentId": "existing-assignment-uuid",
  "sourceHandoverId": "accepted-handover-uuid",
  "expectedAssignmentRevision": 1,
  "expectedTitleRevision": 1,
  "targetOwner": "ISP",
  "reason": "Documented reason for title correction",
  "evidenceId": "committed-work-order-signature-revision-uuid"
}
```

`targetOwner` is a requested transition, not authority over the current owner.
The current asset/customer/WO, title, revisions and installed position are derived
from verified owner state. A no-op transition or stale revision is rejected.
The response supplies `documentId`; submit that ID with `sourceRevision: 0` to
`POST /api/v1/warehouse/approvals/request`. Decisions use the existing
`POST /api/v1/warehouse/approvals/decide` contract.

The existing `TITLE_REACQUISITION` policy selects independent approvers. A
TITLE_CORRECTION requires every configured tier without monetary threshold
exemptions. Its neutral policy comparison value is not an asset valuation or
commercial posting. Receipt and other document cost policies are unchanged.
Requesters, customers, original custodians and their delegated approval authority
cannot self-approve. Rejected, expired and stale requests have no title effect.

Final approval atomically appends `inventory_asset_title_transfer`, its balanced
1EA owner posting, approval effect/delivery receipts and a recovery-state
transition. The assignment's current title/revision is a derived projection of
that record. Existing handovers, assignment history and customer episodes are not
rewritten. CUSTOMER-to-ISP correction remains CUSTOMER_INSTALLED and unavailable;
it is not receipt, return, removal, inspection or recovery completion.

Request and decision replay return their exact original durable outcomes only
after current authority checks. Competing requests for the same source revision
can produce at most one transfer; the other approval becomes stale.

A rejected title correction remains an immutable historical proposal. Its
approval details expose `canRework=false`, and a new generic rework command is
rejected with `409 SOURCE_NOT_VERIFIED`. Create a new correction with current
ownership, revisions and evidence, then submit it for independent approval.
Authorized replay of an earlier command still returns its original result.

## Current ownership and cessation

`GET /api/customers/{customerId}/assets/ownership` requires `customer.onu.view` and
current customer/WO area scope. Its public DTO contains current `legalOwner`,
`ownershipMode`, `assignmentRevision`, `titleRevision`, `handoverId`,
`latestTransferId`, `serviceCeased`, `recoveryRequired`, `recoveryDue`, and
`positionStatus`. It does not expose storage references or warehouse bins.

LOAN acceptance retains the ISP recovery obligation. Customer service termination
reports it due without moving the physical asset. A completed SALE has no reclaim
obligation. An independently approved correction can require or release recovery
through its immutable transition; neither action completes physical recovery.
Customer termination uses the existing customer/subscription owner lifecycle.

Historical installation and acceptance responses remain historical snapshots.
Current views validate the latest transfer chain; restarting does not recompute
title from a mutable ownership-mode dropdown.

## Source seals and upgrades

Both authorization/install and accepted handover enforce the immutable receipt
SKU ownership-mode snapshot. Widening the live SKU allowlist does not authorize
SALE for a loan-only receipt. Default intent remains LOAN.

Acceptance captures immutable origin receipt/line/claim, issue/deployment,
assignment, position and signature evidence. Final validators reconcile source
revisions, exact posting/header/leg cardinality, approval effects, assignment
history and recovery transitions. Tenant assertions execute inside each validator,
including selective constraint timing. New tables use FORCE RLS and tenant FKs.

Forward migrations V175.70 through V175.79 preserve V175.69 and all predecessor
bytes. Older accepted handovers without the contemporaneous origin seal remain
raw history, but validated title reads/corrections reject them for reconciliation;
the migration does not invent acceptance proof or retroactively approve them.

No task22 removal/swap, task23 attribution, RMA, UI or mobile flow is implemented.
