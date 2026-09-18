# Wave 5 Parallel Execution

## Status

- Setup-only checkpoint after task24: 24 tasks complete, 0 blocked, 28 pending.
- Ready lanes are task25, task27 and task29. No child task is complete and no child checkbox may be changed.
- `setup_content_base` is finalized in the follow-up mapping commit before child worktrees are created. Every child is created from that published commit, not from an unpublished index.

## Child ownership map

| Task | Absolute worktree | Local branch | Explicit remote target | Migration namespace |
| --- | --- | --- | --- | --- |
| 25 | `/home/fajar/ftth/warehouse-wave5-task25` | `work/warehouse-task25` | `refs/heads/work/warehouse-task25` | `V175.113` and pre-higher-apply children `V175_113_N` |
| 27 | `/home/fajar/ftth/warehouse-wave5-task27` | `work/warehouse-task27` | `refs/heads/work/warehouse-task27` | `V175.114` and pre-higher-apply children `V175_114_N` |
| 29 | `/home/fajar/ftth/warehouse-wave5-task29` | `work/warehouse-task29` | `refs/heads/work/warehouse-task29` | `V175.115` and pre-higher-apply children `V175_115_N` |

Workers push WIP only to their named remote child branch with an explicit
`HEAD:refs/heads/work/warehouse-taskNN` refspec. They never push to
`feat/warehouse-workorder`, write the integration worktree, or share a Git index.
Each worker lists its exact changed-file delta in its task note and leaves task
checkbox changes to the integration owner after integrated verification.

## Host-wide QA serialization

The outer host lock is:

`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`

Acquire one bounded `flock` around the complete QA lifecycle: isolated `up`,
`check`, every Gradle/HTTP/manual verification step, `stop`, and `down`. The fixed
ports `25432`, `29000`, `17880`, and `14188` must never overlap between workers.
The existing per-worktree lock and ownership markers remain mandatory inside the
outer lock. Release the host lock only after owned cleanup, including failure or
interruption paths. Do not hold it while researching, editing, or waiting for
review.

Each child generates its own private runtime env, environment marker, Compose
identity and retained volumes through the existing runner. Never copy the
integration worktree's private env or another child's marker/volumes. Never prune,
reset, or delete old volumes/data to resolve a collision.

## File ownership and integration

Likely shared files are `WarehouseHttpErrors`, `WarehouseDocumentContracts`,
`WarehouseErrors`, `WarehousePostingService`, `WarehouseDocuments`,
`DurableApprovalService`, and `docs/warehouse-migrations.md`. Isolation prevents
write clobber, but each worker must minimize shared-file edits and report each one.
Only the integration owner reconciles shared additive changes.

- Task25 owns its transfer service/controller/store/contracts and direct tests.
- Task27 owns count/recount service/controller/store/contracts and direct tests;
  it is the primary Wave 5 owner for approval dispatch changes.
- Task29 owns replenishment service/controller/store/contracts and direct tests.
  It reads existing generic documents/projections and must not depend on a new
  task25 API.

After child review, the integration owner cherry-picks reviewed commits in
migration order task25, task27, task29, resolves shared files once, then runs
combined serialized QA. No merge/rebase/force push, no main checkout change, and
no PR at setup time.
