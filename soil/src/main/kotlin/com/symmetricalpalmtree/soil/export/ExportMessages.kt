package com.symmetricalpalmtree.soil.export

import androidx.annotation.StringRes
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.seam.Seam

/** What each failure says. */
object ExportMessages {

    @StringRes
    fun of(problem: ExportArtifact.Problem): Int = when (problem) {
        ExportArtifact.Problem.IN_USE -> R.string.export_in_use_body
        ExportArtifact.Problem.LOCKED -> R.string.export_locked_body
        ExportArtifact.Problem.MISSING -> R.string.export_missing_body
        ExportArtifact.Problem.UNREADABLE -> R.string.export_unreadable_body
        ExportArtifact.Problem.COPY_FAILED -> R.string.export_copy_failed_body
    }

    /** A renderer's refusal, by the message it threw. */
    @StringRes
    fun ofRender(message: String?): Int = when (message) {
        Seam.RENDER_EMPTY -> R.string.export_empty_body
        Seam.RENDER_DAMAGED -> R.string.export_damaged_body
        Seam.RENDER_TOO_LONG -> R.string.export_too_long_body
        else -> R.string.export_render_failed_body
    }
}
