package com.symmetricalpalmtree.soil.seam

import android.os.IBinder
import android.util.Log
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.symmetricalpalmtree.soil.shell.PaperFront

/**
 * **The app in front**, as far as the panel is concerned. A Sprout app attaches its client while
 * its paper screen shows; the side menu and the Scratch Pad ask it to let the panel go before
 * they draw over it, as [com.symmetricalpalmtree.soil.shell.MenuSignals] asks Soil's own paper.
 *
 * One client at a time. An app that dies is detached by its binder's death.
 *
 * Each ask waits a bounded time and never throws: an app that does not answer is an app whose
 * panel Soil draws over anyway, which is what happened before the seam carried the ask.
 */
object SeamClients {

    private const val TAG = "SeamClients"
    private const val WAIT_MS = 600L

    private var client: ISeamClient? = null
    private var death: IBinder.DeathRecipient? = null

    @Synchronized
    fun attach(newClient: ISeamClient) {
        detachCurrent()
        val recipient = IBinder.DeathRecipient { detach(newClient) }
        try {
            newClient.asBinder().linkToDeath(recipient, 0)
        } catch (_: android.os.RemoteException) {
            return
        }
        client = newClient
        death = recipient
        PaperFront.appPaper(true)
        Slog.d(TAG) { "an app's paper is in front" }
    }

    @Synchronized
    fun detach(oldClient: ISeamClient) {
        if (client?.asBinder() != oldClient.asBinder()) return
        detachCurrent()
        PaperFront.appPaper(false)
        Slog.d(TAG) { "the app's paper has left" }
    }

    private fun detachCurrent() {
        val c = client ?: return
        val d = death
        if (d != null) runCatching { c.asBinder().unlinkToDeath(d, 0) }
        client = null
        death = null
    }

    @Synchronized
    private fun current(): ISeamClient? = client

    /** Whether the pen is down or hovering over the app in front. False with no app, or one that
     *  does not answer in time. */
    fun penActive(): Boolean {
        var active = false
        ask("say whether the pen is active") { active = it.penActive() }
        return active
    }

    /** Ask the app in front to let the panel go for a frame, and wait for it, bounded. */
    fun releasePanel() = ask("release the panel") { it.releasePanel() }

    /** Ask the app in front to release the pipeline for a paper screen of Soil's, bounded. */
    fun releaseForHandoff() = ask("release for handoff") { it.releaseForHandoff() }
    /**
     * The call crosses to the app and waits on its main thread; it runs on a thread of its own
     * so that a slow or dead app holds nothing of Soil's for longer than [WAIT_MS].
     */
    private fun ask(what: String, call: (ISeamClient) -> Unit) {
        val c = current() ?: return
        val done = CountDownLatch(1)
        Thread {
            try {
                call(c)
            } catch (e: Exception) {
                Log.w(TAG, "the app did not $what: ${e.javaClass.simpleName}")
            } finally {
                done.countDown()
            }
        }.start()
        if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "the app took too long to $what")
    }
}
