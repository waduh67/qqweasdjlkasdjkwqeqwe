package com.duluin.ftth.mobile.app

import android.content.Context
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.storage.AndroidSecureOutbox
import org.koin.dsl.module

fun androidPlatformModule(context: Context, ports: TechnicianPlatformPorts) = module {
    single { ports }
    single<SecureOutboxPort> { AndroidSecureOutbox(context.applicationContext, ports.identity.userId) }
}
