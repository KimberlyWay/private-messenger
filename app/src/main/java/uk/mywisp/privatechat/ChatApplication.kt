package uk.mywisp.privatechat

import android.app.Application
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform

class ChatApplication : Application() {
    lateinit var repository: MessengerRepository
        private set
    override fun onCreate() {
        super.onCreate()
        org.matrix.rustsdk.Android.init(this)
        initPlatform(TracingConfiguration(LogLevel.ERROR, emptyList(), emptyList(), BuildConfig.ALLOW_TEST_HTTP, null, null), false)
        repository = MessengerRepository(this)
    }
}
