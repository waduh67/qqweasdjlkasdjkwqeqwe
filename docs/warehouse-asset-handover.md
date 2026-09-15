# Customer asset acceptance (task21 work in progress)

The acceptance path is implemented, but task21 is not complete. In particular,
the independently approved post-handover title-correction command is not enabled.
Do not interpret this document or the acceptance tests as completion of task21.

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

## Remaining task21 work

- Independently approved correction request, execution, immutable transfer record
  and CUSTOMER-to-ISP posting without making the installed asset available.
- Immutable receipt ownership-mode admission checks, beyond task20's current SKU
  allowlist check, and complete acceptance snapshot/final-state reconciliation.
- Current title read projections and service-cessation recovery reporting.
- The complete requested race, cross-tenant, adversarial and bounded regression
  matrix, including correction approval and removal-boundary contention.

No task22 removal/swap, task23 attribution, RMA, UI or mobile flow is implemented.
