package fox.foxiru.foxcat.fox2d

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupState @Inject constructor() {
    @Volatile var ready: Boolean = false
        private set

    fun markReady() { ready = true }
}