package dev.xd.bluetrack

import android.app.Application
import dev.xd.bluetrack.core.AppContainer

class BluetrackApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
