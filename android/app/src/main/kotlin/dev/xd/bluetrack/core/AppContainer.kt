package dev.xd.bluetrack.core

import android.content.Context
import dev.xd.bluetrack.ble.BleHidGateway
import dev.xd.bluetrack.engine.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppContainer(
    context: Context,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val translationEngine = TranslationEngine(appScope)
    val bleGateway = BleHidGateway(context.applicationContext, translationEngine)

    fun shutdown() {
        appScope.cancel()
    }
}
