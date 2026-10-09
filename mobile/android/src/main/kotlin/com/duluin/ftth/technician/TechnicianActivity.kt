package com.duluin.ftth.technician

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.duluin.ftth.mobile.app.ReferenceTechnicianApp
import com.duluin.ftth.mobile.data.KtorFieldTransport
import com.duluin.ftth.mobile.data.ReferenceTechnicianRepository
import com.duluin.ftth.mobile.data.TechnicianAuthentication
import com.duluin.ftth.mobile.domain.AccountSecureOutbox
import com.duluin.ftth.mobile.storage.AndroidSecureOutbox
import com.duluin.ftth.mobile.storage.AndroidTechnicianCredentials
import java.util.UUID

class TechnicianActivity : ComponentActivity() {
    private lateinit var photos: AndroidFieldPhotos
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outbox = AccountSecureOutbox { AndroidSecureOutbox(applicationContext, it) }
        val auth = TechnicianAuthentication(KtorFieldTransport(), AndroidTechnicianCredentials(applicationContext),
            { UUID.randomUUID().toString() }, outbox::purge)
        val repository = ReferenceTechnicianRepository(auth, outbox, { UUID.randomUUID().toString() })
        photos = AndroidFieldPhotos(this)
        setContent { ReferenceTechnicianApp(repository, photos) }
    }
    override fun onDestroy() { photos.close(); super.onDestroy() }
}
