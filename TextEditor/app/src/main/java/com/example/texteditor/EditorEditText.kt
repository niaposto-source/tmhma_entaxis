package com.example.texteditor

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText που ειδοποιεί όταν αλλάζει η θέση του δρομέα και δίνει στην εφαρμογή
 * τον έλεγχο της αναίρεσης/επανάληψης (αντί για την ενσωματωμένη του Android).
 */
class EditorEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onSelectionChangedListener: (() -> Unit)? = null
    var onUndoRequest: (() -> Unit)? = null
    var onRedoRequest: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke()
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { onUndoRequest?.invoke(); true }
        android.R.id.redo -> { onRedoRequest?.invoke(); true }
        else -> super.onTextContextMenuItem(id)
    }
}
