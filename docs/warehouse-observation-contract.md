# Warehouse Observation Contract

Task23 remains subject to independent verification. This documents implemented
observation behavior, not release approval.

## Clocks And Attribution

- Collector payloads require non-null `collectedAt` and `observedAt` strings.
  Missing/null required fields are malformed HTTP shapes:400, no dedup marker or
  fabricated observation timestamp. A present but unparsable timestamp is retained
  as unassigned evidence, rather than converted to the ingestion clock.
- Clock admission precedes every provenance quarantine. Out-of-window/extreme
  instants retain their raw payload timestamp with a null typed timestamp; they
  cannot overflow persistence and roll back otherwise valid siblings.
- The72-hour age and5-minute future tolerance are admission windows, not proof
  that future ownership is known. Tolerated future samples become immutable
  `FUTURE_OBSERVATION` evidence; they do not update metrics, live status or alarms.
  They are not automatically finalized later without new evidence.
- Server SNMP polling carries its server-measured acquisition interval separately
  from device timestamp claims. A poll spanning an episode or path transition is
  `POLL_SPANS_TRANSITION`, never relabelled as a sample of the replacement ONU.
- Metric times use microsecond precision. Accepted pipeline metrics retain source,
  receipt time, decision-time episode/assignment revisions, and the topology and
  network edge revisions selected at observation time. Decision-time revisions
  are not represented as the revision that existed at acquisition time.
- Legacy/direct rows without producer evidence remain explicitly unverified;
  their origin is not backfilled. Existing metric bytes are retained and updates
  are prohibited. Timescale chunk retention does not delete episode/path parents.

## Path Evidence

- ONU attachment transitions use the server clock after row serialization.
  A non-advancing clock is rejected rather than repaired by sorting/backdating.
- Network-owned ODP/ODC/PON edge history captures upstream moves independently of
  ONU attachment rows. Customer attribution reads it through a public network API.
  Unknown pre-expansion upstream history is unassigned, not reconstructed from
  today's mutable topology.
- Current GPON profiles retain raw ONU table indexes as `UNVERIFIED_INDEX`, never
  configured labels such as `1/1/1`. A unique active legacy ONU with no ODP/path
  can accept valid-time telemetry from an OLT belonging to its tenant as
  `EPISODE_ONLY`; this does not assert a verified PON path or create stock.
  Unknown/ambiguous episodes, foreign/unresolved OLTs and connected unresolved
  paths remain unassigned. Connected path mismatch checks are unchanged.
- Connected GPON mapping remains blocked pending authoritative evidence. Existing
  owner data maps `(OLT, configured label)` to a PON, not raw ONU indexes. Required
  evidence is a vendor/model/firmware-specific SNMP index+serial capture paired
  with authoritative CLI/API frame/slot/PON identity across multiple ports/ONU
  positions, plus the matching MIB/index semantics. No bit-shift decoder or raw
  index relabelling is inferred from one example integer.

## Concurrency And Replay

- Observation ingest and physical removal can contend on ONU/asset locks. A
  database concurrency victim returns409 `OBSERVATION_RETRY`; the entire failed
  ingest transaction, including its batch marker, rolls back.
- The collector retries409 with the original batch ID. Authentication/configuration
  failures such as401 remain permanent. Retry does not create a second movement,
  assignment, receipt or metric.
- Batch retention uses the later receipt/retention anchor. Migration conservatively
  extends old markers with uncertain transaction-start clocks. Exact cutoff rows
  remain; only rows strictly older than the cutoff are purged in their tenant.
- Global CPE owner censuses include suspended tenants and use short database
  transactions. No ownership fence is held across ACS network I/O. A device-only
  operation mutex serializes ACS commands, with fresh owner checks after waiting
  and after I/O before returning results or recording success.
- Database-only cache admission retains ownership coordination through commit.
  Cached data cannot become readable solely because it was admitted before a new
  conflicting owner appeared.
- ACS202 is queued, not diagnostic completion. Completion requires post-request
  parameter timestamps and matching request inputs; stale or missing evidence is
  incomplete. Every timestamp must satisfy the bounds, not only the oldest one.
- Legacy compatibility permits missing timestamp evidence, never explicitly
  malformed or out-of-bounds parameter times. Missing siblings cannot conceal a
  known-invalid timestamp; the gateway carries that distinction through reads and
  cache admission.

## Verify-02 Correlation And Database Truth

- Diagnostics first write a unique nonce into a writable diagnostic input without
  starting a test. They explicitly refresh and observe the nonce plus `None` in a
  newer Inform before restoring requested inputs and starting the new generation.
  Broadband Forum diagnostics require writable-input changes to terminate/reset
  an in-progress test. NBI GET alone is only a cache read: reset uses
  `getParameterValues`, and results refresh the diagnostic object rather than
  requesting unsupported vendor/direction-specific leaves. Pending tasks, faults, ambiguous reset,
  stale/future results or missing identity/Inform yield incomplete, not success.
- Successful results require distinct acknowledged task identities, newer
  trustworthy Inform and parameter evidence and matching requested inputs. A
  pending/expired earlier request is not identified by the database operation
  lock alone. A device that cannot confirm the generation transition cannot safely
  report completion; compliant fresh generations remain supported.
- Eligibility is rechecked after preparatory network reads and immediately before
  every diagnostic task POST and firmware dispatch, then again before result/history.
  Already-dispatched external work cannot roll back with a local transaction.
- Known parameter minima survive missing siblings. Explicitly pre-episode data is
  rejected even for legacy; missing-only compatibility remains. Current CPE reads
  require READ COMMITTED rather than assuming an advisory-lock wait refreshes a
  REPEATABLE READ snapshot.
- Receipt validation hashes the original canonical text's UTF8 bytes and compares
  both consumed and operation hashes. It never reconstructs canonical bytes from
  jsonb rendering. Valid legacy no-observation-context receipts retain their old
  shape, but still require canonical hash truth.
- `BOUND` metric evidence must exactly match the latest applicable nondeleted
  ODP/ODC/PON chain and OLT. An actually unattached episode uses `EPISODE_ONLY`,
  not a verified path claim. Downgrading a connected claim cannot bypass validation.
- New metrics protect their episode interval through row serialization and
  mutation-side deferred checks. Earlier unverified legacy data remains retained;
  no observation is retimed or deleted to allow an invalid closure.

Protocol references:
https://docs.genieacs.com/en/stable/api-reference.html
https://github.com/genieacs/genieacs/blob/4aa5cbfa33c40d17fcbb0b391ce2262e5e70dd79/lib/nbi.ts
https://cwmp-data-models.broadband-forum.org/tr-181-2-17-0-cwmp-diffs.html
