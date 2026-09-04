package com.duluin.ftth.mobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

abstract class MainActivity : ComponentActivity() {
    protected abstract val dependencies: TechnicianAppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TechnicianApp(dependencies) }
    }
}
