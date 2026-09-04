package com.duluin.ftth.mobile.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(dependencies: TechnicianAppDependencies): UIViewController =
    ComposeUIViewController { TechnicianApp(dependencies) }
