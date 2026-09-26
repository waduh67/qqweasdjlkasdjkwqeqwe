# Activation helper recheck — approved for staging only

The corrected helper closes the earlier approval and recovery findings. Source SHA256 is `87f7e0f55c82bad649b3d5ed615b7e5b18227992bfe664ecd649095d5c0c3ed7`. No activation or final F4 approval is granted by this source review.

The approval gate now requires exactly13 host-file hashes, including the private environment, six source-bound evidence files, current CI36265279847 with all16 jobs completed successfully, backup-drill success, and F1–F4 readiness receipts bound to the same code/configuration, evidence, host and helper hashes. Existing canonical proof schemas match these requirements. An absent/incomplete/stale approval record fails before mutation.

After an attempted public switch, caught failures restore only the previous managed FTTH block, preserve concurrent unrelated configuration, validate and explicitly reload the restored proxy, and require the direct-origin TLS response to return503. Concurrent edits to the managed block stop for manual recovery. TERM/INT enter the caught path; repeated signals are ignored during recovery and at the start of final receipt persistence. Migrated data/history remain intact. SIGKILL or power loss require the retained prior file and durable stage for manual recovery.

The saved synthetic log independently records14 named tests, all passing, with exit0. Its receipt, log and exact helper/test hashes match. Coverage includes missing inventory/evidence/jobs, wrong CI, stale approvals, unrelated/concurrent edits, reload failure and real local signal delivery into mocked recovery. These are local synthetic checks and do not establish host Caddy/TLS/runtime recovery.

F3 readiness is not circular: its receipt does not hash itself, other review receipts or the approval file. The existing F3 reviewer can bind already accepted browser evidence to unchanged1306 application bytes once current CI passes, without repeating browser QA solely for deployment changes. READY_FOR_ACTIVATION must remain a pre-activation scope decision, not a claim that public HTTPS/login/Drive/backups or final host acceptance already succeeded.

The helper remains staged and unexecuted according to the parent. Current complete code CI, actual readiness receipts and subsequent activation verification remain pending. F4 performed read-only source/log/hash review and did not run QA, Docker, SSH or deployment.
