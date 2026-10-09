package com.duluin.ftth.mobile.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import platform.Foundation.NSUUID
import com.duluin.ftth.mobile.data.KtorFieldTransport
import com.duluin.ftth.mobile.data.ReferenceTechnicianRepository
import com.duluin.ftth.mobile.data.TechnicianAuthentication
import com.duluin.ftth.mobile.domain.AccountSecureOutbox
import com.duluin.ftth.mobile.storage.IosSecureOutbox
import com.duluin.ftth.mobile.storage.IosTechnicianCredentials

fun MainViewController(): UIViewController {
    val outbox = AccountSecureOutbox(::IosSecureOutbox)
    val auth = TechnicianAuthentication(KtorFieldTransport(), IosTechnicianCredentials(), { NSUUID().UUIDString }, outbox::purge)
    val repository = ReferenceTechnicianRepository(auth, outbox, { NSUUID().UUIDString })
    lateinit var controller: UIViewController
    val photos = IosFieldPhotos { controller }
    controller = ComposeUIViewController { ReferenceTechnicianApp(repository, photos) }
    return controller
}

fun MainViewController(
    userId: String,
    ports: TechnicianPlatformPorts,
): UIViewController = ComposeUIViewController {
    TechnicianApp(iosPlatformModule(userId, ports))
}
