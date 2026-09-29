package com.symmetricalpalmtree.soil.bootstrap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.home.HomeActivity
import com.symmetricalpalmtree.soil.paper.core.Dialogs

/**
 * The screens of Soil that need the key, and the one way to open them: through [KeyGate].
 *
 * A caller says which screen it wants; when the gate is shut it is taken to the screen that opens
 * it — the recovery key, or unlock — and on from there. **What rides the Intent is the name of a
 * screen, never anything of a key.**
 */
enum class Screen {
    HOME;

    companion object {
        fun named(name: String?): Screen? = values().firstOrNull { it.name == name }
    }
}

object Screens {

    /** The screen to go on to once the gate has been opened. A [Screen] name. */
    const val EXTRA_THEN = "com.symmetricalpalmtree.soil.extra.THEN"

    /**
     * Open [screen], or whatever must come first. From an [Activity] a shut gate that nothing can
     * open is explained in a dialog; from anywhere else (the side menu, which has no window of its
     * own to hang one on) in a toast.
     */
    fun open(context: Context, screen: Screen) {
        when (Library.status.value.route) {
            KeyGate.Route.OPEN -> start(context, target(context, screen))
            KeyGate.Route.RECOVERY_KEY -> start(context, Intent(context, RecoveryKeyActivity::class.java).then(screen))
            KeyGate.Route.UNLOCK -> start(context, Intent(context, UnlockActivity::class.java).then(screen))
            // The Encryption screen and its resume banner arrive with step 7.
            KeyGate.Route.RESUME_ROTATION -> explain(context, R.string.gate_rotating_title, R.string.gate_rotating_body)
            KeyGate.Route.PREPARING -> explain(context, R.string.gate_preparing_title, R.string.gate_preparing_body)
            KeyGate.Route.BLOCKED -> explain(context, R.string.gate_blocked_title, R.string.gate_blocked_body)
        }
    }

    /** Go on to the screen named on [from]'s Intent, if it names one. */
    fun openThen(from: Activity) {
        val then = Screen.named(from.intent.getStringExtra(EXTRA_THEN)) ?: return
        if (then != Screen.HOME) open(from, then)
    }

    fun target(context: Context, screen: Screen): Intent = when (screen) {
        Screen.HOME -> Intent(context, HomeActivity::class.java)
    }

    private fun Intent.then(screen: Screen): Intent = putExtra(EXTRA_THEN, screen.name)

    private fun start(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun explain(context: Context, titleRes: Int, bodyRes: Int) {
        if (context is Activity) Dialogs.problem(context, titleRes, bodyRes)
        else Toast.makeText(context, bodyRes, Toast.LENGTH_LONG).show()
    }
}
