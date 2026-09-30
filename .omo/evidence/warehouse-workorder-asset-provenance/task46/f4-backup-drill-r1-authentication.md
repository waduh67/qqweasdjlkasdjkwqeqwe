# Backup drill authentication

The shipped backup and non-replacing restore scripts passed an isolated drill. All archive members and captured source hashes match; the3143243-byte custom-format dump matches its recorded checksum. The exit file is0, the unit journal records PASS and successful completion, and scoped cleanup is0.

The fresh restore contains the same367 migration rows,1 tenant,1 user,0 customers and0 draft-policy rows as the source. Executed assertions verify the migration/database owner, non-owner runtime role, forced RLS, SELECT-only clock-table permissions and expiry-function grants. The source database and deployed script bytes remained unchanged. Raw logs show one restore, Timescale pre/post-restore steps and no restore-error markers. No `--replace` mode or production database was used; retained QA volumes and drill DB remain private.

This proves backup/restore behavior for the platform-only fixture. It is not populated production/legacy disaster-recovery evidence. Raw dump/globals/logs stay private; exact hashes, source bindings and limits are in the paired JSON. F4 did not rerun backup/restore, query a database, use SSH or run Docker. Current full CI and final release acceptance remain pending.
