package io.github.lectorsys.symus

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.lectorsys.symus.media.Extractor
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class SymusApp : Application() {

    @Inject
    lateinit var client: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // Static, global, and required before any extractor call — including one
        // made from a service the system started without an Activity, which is
        // why it happens here and not in the first screen.
        Extractor.init(client)
    }
}
