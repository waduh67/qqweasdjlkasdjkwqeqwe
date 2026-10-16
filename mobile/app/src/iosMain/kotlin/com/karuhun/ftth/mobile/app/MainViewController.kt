package com.karuhun.ftth.mobile.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import platform.Foundation.NSUUID
import com.karuhun.ftth.mobile.data.KtorFieldTransport
import com.karuhun.ftth.mobile.data.ReferenceTechnicianRepository
import com.karuhun.ftth.mobile.data.TechnicianAuthentication
import com.karuhun.ftth.mobile.domain.AccountSecureOutbox
import com.karuhun.ftth.mobile.storage.IosSecureOutbox
import com.karuhun.ftth.mobile.storage.IosTechnicianCredentials

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
