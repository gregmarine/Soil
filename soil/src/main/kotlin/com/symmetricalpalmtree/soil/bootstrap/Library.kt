package com.symmetricalpalmtree.soil.bootstrap

import android.content.Context
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What every screen needs to know about the library's keys, as one value that can be read on the
 * main thread: the index's state and the two flags kept beside the key. The flags live in
 * Keystore-backed storage, so they are read on IO, here, and nowhere else.
 */
object Library {

    data class Status(
        val index: SoilIndex.State = SoilIndex.State.PREPARING,
        val acknowledged: Boolean = false,
        val rotating: Boolean = false,
    ) {
        val route: KeyGate.Route get() = KeyGate.route(index, acknowledged, rotating)
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    /** Follow the index for as long as the process lives. Called once, by the application. */
    fun follow(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch { SoilIndex.state.collect { refresh(app) } }
    }

    /** Re-read the flags. Call after anything that changes one: an acknowledgement, a rotation. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val index = SoilIndex.state.value
        _status.value = withContext(Dispatchers.IO) {
            // Before the index has been looked at the storage has not been opened either, and
            // nothing it holds could change the answer.
            if (index == SoilIndex.State.PREPARING) Status(index)
            else Status(
                index = index,
                acknowledged = PassphraseStore.isRecoveryKeyAcknowledged(app),
                rotating = PassphraseStore.getRotationMarker(app) != null,
            )
        }
    }
}
