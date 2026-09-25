# Task46 pending regression work

Task45 remains IN PROGRESS. No full regression gate claimed yet.

1. WorkOrderMaterialUsageITProjectionUpgrade runs current application services against
   a schema stopped at175.21. New receipt/source readers require later columns
   (transfer_receiver_id), so initial consumedCase fails before intended historical
   corruption. Restore a version-correct historical fixture/runner; do not skip the
   test, add production old-schema fallbacks, weaken authorization, or fabricate a
   passing HTTP409 from an unrelated missing-column error. Historical source commits:
   c164c4eb added test;94d6d2ec added immutableV175.22 guard;6f7d2426 changed fixture later.
   Current projection regressions and the historical upgrade contract must both pass.

2. Actual task45 browser customer creation repeatedly logs PortalCustomerContactListener
   failure: InvalidDataAccessApiUsageException / TransactionRequiredException, No active
   transaction. Source: portal/application/service/PortalCustomerContactListener.kt
   handles AFTER_COMMIT, invokes PortalIdentitySyncService.sync with REQUIRED. That
   joins completed transaction resources. Need real HTTP regression showing credential
   username/email/phone index changes after committed contact edit, rejects old contact,
   preserves unrelated/other-tenant accounts and ignores rolled-back contact changes.
   Keep ordinary credential-service sync within its caller transaction; do not globally
   switch sync to REQUIRES_NEW and lose uncommitted credential visibility. A separate
   committed-contact entry point invoked inside TenantContext.runAs can establish a
   new transaction. This finding has NOT been fixed or tested yet.

3. Run full server + ModularityTests, web lint/unit/build/E2E typecheck, KMP shared and
   actual macOS native compile gate. Preserve full reports before focused reruns.
   Native compilation is not hardware/runtime/release proof. No subagents authorized.
