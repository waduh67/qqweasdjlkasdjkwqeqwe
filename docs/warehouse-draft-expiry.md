# Idle warehouse drafts

Idle drafts have a database deadline. When it is reached, current reads expose
`EXPIRED` with `draftExpiry.deadline`, `reason: IDLE_DEADLINE`, and an optional
`recordedAt` indicating when the expiry worker persisted the terminal record.
The deadline is effective even while scheduling is disabled or the worker has
not reached that source yet. A new command against a due source returns
`409 DRAFT_EXPIRED`.

This applies to receipts (including supplier replacement proposals), transfers,
counts that have never started, unsubmitted material plans, and sealed loss,
scrap, reversal, asset-loss, ownership, transfer-adjustment and opening-balance
proposals. Submitted plans, dispatched transfers, started counts and posted
documents continue through their existing workflows.

Expiry creates no stock, reservation, admission, custody or ownership effect.
It retains source rows, lines, evidence, revisions and original command responses.
Authorized retries with the same key and body return the original response;
clients reload current detail after a command. Approval detail keeps the sealed
document snapshot and reports its current source state separately. Material
summary likewise separates `planState` from `demandState`; an expired latest plan
does not disappear or cause an older plan to become current. A replacement plan
gets a new identity and the next retained plan revision.

The initial policy is seven days. Policy versions are managed by the database
owner; application roles cannot insert, change, truncate or delete the protected
clock tables. A new identity pins the latest policy version. A later policy
change does not reset existing deadlines. Only an accepted explicit receipt,
transfer or count draft save renews that source, using its pinned lifetime and
database time after source locking. Reads, attachments, retries, approval actions,
control updates and rework do not renew it. Sealed proposals and material-plan
identities have creation deadlines; corrections create a new proposal or plan.

The expiry worker processes active tenants in bounded batches. It holds the
cutover and authority fences before source and ordered approval locks. Source
expiry and pending-approval termination commit together; an isolated expiry row
cannot commit while a pending approval remains. Late approval attempts also
terminate without a physical effect and retain their original result for replay.

Migration `178.12` adds the protected policy, activity and terminal side tables.
It locks legacy writers before the baseline. A legacy timestamp is bounded to
the interval from one policy lifetime before migration to migration time, giving
a deadline between migration time and one lifetime afterward. Nonfinite legacy
timestamps use the lower bound. No old source timestamp or command response is
rewritten. Eligible sources missing protected activity are integrity failures;
current reads never invent a new deadline for them.

Use the current detail's explanation and create a fresh draft or proposal from
its source workflow. Expiry is not a tenant-erasure mechanism: tenants with
protected warehouse history must be suspended instead of deleted.
