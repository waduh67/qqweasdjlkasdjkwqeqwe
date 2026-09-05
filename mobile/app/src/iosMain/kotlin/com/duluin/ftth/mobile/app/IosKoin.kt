package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.storage.IosSecureOutbox
import org.koin.dsl.module

fun iosPlatformModule(userId: String, ports: TechnicianPlatformPorts) = module {
    single { ports }
    single<SecureOutboxPort> { IosSecureOutbox(userId) }
}
