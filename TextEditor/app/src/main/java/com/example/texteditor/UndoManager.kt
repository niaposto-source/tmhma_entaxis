package com.example.texteditor

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Απλό ιστορικό αναίρεσης/επανάληψης. Καταγράφει κάθε αλλαγή ως (θέση, κείμενο που
 * αφαιρέθηκε, κείμενο που μπήκε) και συγχωνεύει διαδοχικές πληκτρολογήσεις/διαγραφές.
 */
class UndoManager(
    private val editText: EditText,
    private val onHistoryChanged: () -> Unit
) : TextWatcher {

    private class Edit(var start: Int, var removed: String, var added: String)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var pendingRemoved = ""
    private var applying = false

    /** Όταν είναι false οι αλλαγές δεν καταγράφονται (π.χ. κατά τη φόρτωση αρχείου). */
    var enabled = true

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        onHistoryChanged()
    }

    fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        if (apply(edit.start, edit.added.length, edit.removed)) redoStack.addLast(edit) else clear()
        onHistoryChanged()
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        if (apply(edit.start, edit.removed.length, edit.added)) undoStack.addLast(edit) else clear()
        onHistoryChanged()
    }

    private fun apply(start: Int, length: Int, replacement: String): Boolean {
        val text = editText.text
        if (start < 0 || start + length > text.length) return false
        applying = true
        try {
            text.replace(start, start + length, replacement)
            editText.setSelection(start + replacement.length)
        } finally {
            applying = false
        }
        return true
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        if (!enabled || applying) return
        pendingRemoved = s.subSequence(start, start + count).toString()
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        if (!enabled || applying) return
        record(Edit(start, pendingRemoved, s.subSequence(start, start + count).toString()))
    }

    override fun afterTextChanged(s: Editable) = Unit

    private fun record(edit: Edit) {
        val hadUndo = canUndo
        val hadRedo = canRedo
        redoStack.clear()

        val prev = undoStack.lastOrNull()
        if (prev == null || !merge(prev, edit)) {
            undoStack.addLast(edit)
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        }

        if (hadUndo != canUndo || hadRedo != canRedo) onHistoryChanged()
    }

    /** Επιστρέφει true αν η νέα αλλαγή απορροφήθηκε από την προηγούμενη. */
    private fun merge(prev: Edit, next: Edit): Boolean {
        // Συνεχόμενη πληκτρολόγηση (και ορθογραφικά «προτεινόμενα» του πληκτρολογίου)
        if (next.removed.isEmpty() && prev.removed.isEmpty() &&
            next.start == prev.start + prev.added.length &&
            prev.added.length < MAX_MERGE && !next.added.contains('\n') && !prev.added.contains('\n')
        ) {
            prev.added += next.added
            return true
        }
        // Το πληκτρολόγιο αντικαθιστά το κείμενο σύνθεσης με μεγαλύτερο
        if (next.start == prev.start && prev.added.isNotEmpty() && next.removed == prev.added &&
            prev.added.length < MAX_MERGE
        ) {
            prev.added = next.added
            return true
        }
        // Διαδοχικά backspace
        if (next.added.isEmpty() && prev.added.isEmpty() && next.start + next.removed.length == prev.start) {
            prev.start = next.start
            prev.removed = next.removed + prev.removed
            return true
        }
        // Διαδοχικά delete
        if (next.added.isEmpty() && prev.added.isEmpty() && next.start == prev.start) {
            prev.removed += next.removed
            return true
        }
        return false
    }

    private companion object {
        const val MAX_HISTORY = 1000
        const val MAX_MERGE = 40
    }
}
