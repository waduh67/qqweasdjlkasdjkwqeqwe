package com.duluin.ftth.mobile.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(
    userId: String,
    ports: TechnicianPlatformPorts,
): UIViewController = ComposeUIViewController {
    TechnicianApp(iosPlatformModule(userId, ports))
}
