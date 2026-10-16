package com.karuhun.ftth.mobile.app

import com.karuhun.ftth.mobile.domain.SecureOutboxPort
import com.karuhun.ftth.mobile.storage.IosSecureOutbox
import org.koin.dsl.module

fun iosPlatformModule(userId: String, ports: TechnicianPlatformPorts) = module {
    single { ports }
    single<SecureOutboxPort> { IosSecureOutbox(userId) }
}
