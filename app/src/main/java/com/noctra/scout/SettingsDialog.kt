package com.noctra.scout

import android.content.Context
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noctra.scout.databinding.DialogSettingsBinding

/** Compact settings sheet: character set, pacing, optional token, auto-stop. */
object SettingsDialog {

    fun show(context: Context, prefs: Prefs, onSaved: () -> Unit) {
        val b = DialogSettingsBinding.inflate(android.view.LayoutInflater.from(context))

        val charsetLabels = listOf(
            NameSpace.CHARSET_LETTERS,
            NameSpace.CHARSET_ALNUM,
            NameSpace.CHARSET_ALNUM_UNDERSCORE
        ).map { id ->
            val space = NameSpace.of(id)
            "${NameSpace.label(id)}  ·  ${space.total} names"
        }
        b.charset.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, charsetLabels)
        )
        b.charset.setText(charsetLabels[prefs.charsetId.coerceIn(0, 2)], false)
        var chosenCharset = prefs.charsetId
        b.charset.setOnItemClickListener { _, _, position, _ -> chosenCharset = position }

        b.delay.setText(prefs.delayMs.toString())
        b.stopAfter.setText(prefs.stopAfter.toString())
        b.token.setText(prefs.token)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_settings)
            .setView(b.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newDelay = b.delay.text.toString().toIntOrNull() ?: Prefs.DEFAULT_DELAY_MS
                prefs.delayMs = newDelay
                prefs.stopAfter = b.stopAfter.text.toString().toIntOrNull() ?: 0
                prefs.token = b.token.text.toString()
                if (chosenCharset != prefs.charsetId) {
                    // A different alphabet is a different walk, so the cursor has to restart.
                    // The log is kept: names already answered stay in the tabs.
                    prefs.charsetId = chosenCharset
                    prefs.resetProgress()
                }
                onSaved()
            }
            .show()
    }
}
