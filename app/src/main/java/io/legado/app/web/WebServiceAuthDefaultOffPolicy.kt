package io.legado.app.web

internal object WebServiceAuthDefaultOffPolicy {
    data class State(val enabled: Boolean, val migrated: Boolean)

    fun migrate(currentEnabled: Boolean, migrated: Boolean): State =
        if (migrated) State(currentEnabled, true) else State(false, true)
}
