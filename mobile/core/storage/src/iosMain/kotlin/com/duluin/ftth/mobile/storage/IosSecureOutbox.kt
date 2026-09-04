package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.SecureOutboxPort

class IosSecureOutbox(records: SecureOutboxRecords, cipher: OutboxCipher) : SecureOutboxPort by EncryptedOutbox(records, cipher)
