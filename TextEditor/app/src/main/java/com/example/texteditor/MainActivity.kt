package com.example.texteditor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.texteditor.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var undoManager: UndoManager

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var currentUri: Uri? = null
    private var fileName: String? = null
    private var modified = false
    private var loading = false
    private var pendingAfterSave: (() -> Unit)? = null

    private val editor get() = binding.editor

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Δεν είναι όλοι οι πάροχοι αρχείων «μόνιμοι» — δεν πειράζει.
                }
                loadFromUri(uri)
            }
        }

    private val createLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val after = pendingAfterSave
            pendingAfterSave = null
            if (uri != null) {
                currentUri = uri
                fileName = queryName(uri)
                writeTo(uri, after, fallbackToSaveAs = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            prefs.getInt(KEY_NIGHT, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        undoManager = UndoManager(editor) { invalidateOptionsMenu() }
        editor.addTextChangedListener(undoManager)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loading && !modified) {
                    modified = true
                    updateTitle()
                }
                scheduleStatusUpdate()
            }
        })
        editor.onSelectionChangedListener = { scheduleStatusUpdate() }
        editor.onUndoRequest = { undoManager.undo() }
        editor.onRedoRequest = { undoManager.redo() }

        applyViewPrefs()
        setupFindBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.findBar.visibility == View.VISIBLE) {
                    closeFindBar()
                } else {
                    confirmDiscard { finish() }
                }
            }
        })

        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            currentUri = savedInstanceState.getString(STATE_URI)?.let(Uri::parse)
            fileName = savedInstanceState.getString(STATE_NAME)
        }
        updateTitle()
        updateStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URI, currentUri?.toString())
        outState.putString(STATE_NAME, fileName)
        outState.putBoolean(STATE_MODIFIED, modified)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Η επαναφορά του κειμένου από το σύστημα μετράει ως «αλλαγή» — την αναιρούμε εδώ.
        undoManager.clear()
        modified = savedInstanceState.getBoolean(STATE_MODIFIED)
        updateTitle()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdown() // οι εγγραφές που εκκρεμούν ολοκληρώνονται κανονικά
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Μενού

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        setItemEnabled(menu.findItem(R.id.action_undo), undoManager.canUndo)
        setItemEnabled(menu.findItem(R.id.action_redo), undoManager.canRedo)
        menu.findItem(R.id.action_wrap).isChecked = prefs.getBoolean(KEY_WRAP, true)
        menu.findItem(R.id.action_mono).isChecked = prefs.getBoolean(KEY_MONO, true)
        menu.findItem(R.id.action_dark).isChecked = isDarkNow()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setItemEnabled(item: MenuItem, enabled: Boolean) {
        item.isEnabled = enabled
        item.icon?.mutate()?.alpha = if (enabled) 255 else 90
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new -> confirmDiscard { newDocument() }
            R.id.action_open -> confirmDiscard { openLauncher.launch(arrayOf("*/*")) }
            R.id.action_save -> save()
            R.id.action_save_as -> saveAs()
            R.id.action_undo -> undoManager.undo()
            R.id.action_redo -> undoManager.redo()
            R.id.action_find -> openFindBar()
            R.id.action_goto -> showGoToLine()
            R.id.action_wrap -> {
                prefs.edit().putBoolean(KEY_WRAP, !item.isChecked).apply()
                applyViewPrefs()
            }
            R.id.action_mono -> {
                prefs.edit().putBoolean(KEY_MONO, !item.isChecked).apply()
                applyViewPrefs()
            }
            R.id.action_font_up -> changeTextSize(+2f)
            R.id.action_font_down -> changeTextSize(-2f)
            R.id.action_dark -> {
                val mode = if (isDarkNow()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                prefs.edit().putInt(KEY_NIGHT, mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode) // ξαναδημιουργεί την οθόνη
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------ Αρχεία και αποθήκευση

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data?.let(::loadFromUri)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                setEditorText(text)
                modified = true
            }
        }
    }

    private fun newDocument() {
        setEditorText("")
        currentUri = null
        fileName = null
        modified = false
        updateTitle()
    }

    /** Αντικαθιστά το κείμενο χωρίς να το μετρήσει ως αλλαγή του χρήστη. */
    private fun setEditorText(text: String) {
        loading = true
        undoManager.enabled = false
        editor.setText(text)
        editor.setSelection(0)
        undoManager.enabled = true
        loading = false
        modified = false
        undoManager.clear()
        updateTitle()
        updateStatus()
    }

    private fun loadFromUri(uri: Uri) {
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("No input stream")
                when {
                    bytes.size > MAX_BYTES -> toast(getString(R.string.file_too_large, MAX_BYTES / (1024 * 1024)))
                    looksBinary(bytes) -> toast(getString(R.string.not_text))
                    else -> {
                        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                        val name = queryName(uri)
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            setEditorText(text)
                            currentUri = uri
                            fileName = name
                            updateTitle()
                        }
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.open_failed))
            }
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    private fun save(after: (() -> Unit)? = null) {
        val uri = currentUri
        if (uri == null) saveAs(after) else writeTo(uri, after, fallbackToSaveAs = true)
    }

    private fun saveAs(after: (() -> Unit)? = null) {
        pendingAfterSave = after
        createLauncher.launch(fileName ?: "${getString(R.string.untitled)}.txt")
    }

    private fun writeTo(uri: Uri, after: (() -> Unit)?, fallbackToSaveAs: Boolean) {
        val text = editor.text.toString()
        io.execute {
            try {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                runOnUiThread {
                    modified = false
                    updateTitle()
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    after?.invoke()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (fallbackToSaveAs) {
                        // π.χ. αρχείο που ανοίχτηκε μόνο για ανάγνωση από άλλη εφαρμογή
                        Toast.makeText(this, R.string.save_readonly, Toast.LENGTH_LONG).show()
                        currentUri = null
                        saveAs(after)
                    } else {
                        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Αν υπάρχουν μη αποθηκευμένες αλλαγές ρωτά τι να γίνει, αλλιώς εκτελεί απευθείας την ενέργεια. */
    private fun confirmDiscard(action: () -> Unit) {
        if (!modified) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(getString(R.string.unsaved_message, fileName ?: getString(R.string.untitled)))
            .setPositiveButton(R.string.action_save) { _, _ -> save(action) }
            .setNegativeButton(R.string.discard) { _, _ -> action() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun queryName(uri: Uri): String? {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return name ?: uri.lastPathSegment
    }

    // ------------------------------------------------------------ Εμφάνιση

    private fun updateTitle() {
        supportActionBar?.title =
            (if (modified) "• " else "") + (fileName ?: getString(R.string.untitled))
    }

    private fun applyViewPrefs() {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getFloat(KEY_SIZE, DEFAULT_SIZE))
        editor.typeface = if (prefs.getBoolean(KEY_MONO, true)) Typeface.MONOSPACE else Typeface.DEFAULT
        editor.setHorizontallyScrolling(!prefs.getBoolean(KEY_WRAP, true))
    }

    private fun changeTextSize(delta: Float) {
        val size = (prefs.getFloat(KEY_SIZE, DEFAULT_SIZE) + delta).coerceIn(MIN_SIZE, MAX_SIZE)
        prefs.edit().putFloat(KEY_SIZE, size).apply()
        applyViewPrefs()
    }

    private fun isDarkNow(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val statusRunnable = Runnable { updateStatus() }

    private fun scheduleStatusUpdate() {
        handler.removeCallbacks(statusRunnable)
        handler.postDelayed(statusRunnable, 150)
    }

    private fun updateStatus() {
        val text = editor.text
        val cursor = editor.selectionStart.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until cursor) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        var words = 0
        var inWord = false
        for (i in 0 until text.length) {
            val isSpace = text[i].isWhitespace()
            if (!isSpace && !inWord) words++
            inWord = !isSpace
        }
        binding.status.text = getString(R.string.status_format, line, cursor - lineStart + 1, words, text.length)
    }

    // ------------------------------------------------------ Μετάβαση σε γραμμή

    private fun showGoToLine() {
        val text = editor.text
        val total = 1 + text.count { it == '\n' }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.goto_hint, total)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val pad = dp(24)
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goto_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val target = input.text.toString().toIntOrNull()?.coerceIn(1, total)
                if (target != null) {
                    var offset = 0
                    var line = 1
                    while (line < target) {
                        if (text[offset] == '\n') line++
                        offset++
                    }
                    editor.setSelection(offset)
                    editor.requestFocus()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --------------------------------------------------- Εύρεση / αντικατάσταση

    private fun setupFindBar() {
        binding.btnNext.setOnClickListener { findNext(forward = true) }
        binding.btnPrev.setOnClickListener { findNext(forward = false) }
        binding.btnCloseFind.setOnClickListener { closeFindBar() }
        binding.btnReplace.setOnClickListener { replaceCurrent() }
        binding.btnReplaceAll.setOnClickListener { replaceAll() }
        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext(forward = true)
                true
            } else {
                false
            }
        }
    }

    private fun openFindBar() {
        binding.findBar.visibility = View.VISIBLE
        val start = minOf(editor.selectionStart, editor.selectionEnd)
        val end = maxOf(editor.selectionStart, editor.selectionEnd)
        if (start in 0 until end && end - start <= 200) {
            binding.findInput.setText(editor.text.substring(start, end))
        }
        binding.findInput.requestFocus()
        binding.findInput.selectAll()
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindBar() {
        binding.findBar.visibility = View.GONE
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.findInput.windowToken, 0)
        editor.requestFocus()
    }

    private fun findNext(forward: Boolean): Boolean {
        val query = binding.findInput.text.toString()
        if (query.isEmpty()) return false
        val text = editor.text.toString()
        val ignoreCase = !binding.matchCase.isChecked
        val selStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val selEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)

        var index = if (forward) {
            text.indexOf(query, selEnd, ignoreCase)
        } else {
            val from = selStart - query.length
            if (from >= 0) text.lastIndexOf(query, from, ignoreCase) else -1
        }
        if (index < 0) { // συνέχεια από την άλλη άκρη του κειμένου
            index = if (forward) text.indexOf(query, 0, ignoreCase)
            else text.lastIndexOf(query, text.length, ignoreCase)
        }
        if (index < 0) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
            return false
        }
        editor.setSelection(index, index + query.length)
        editor.requestFocus() // η επιλογή φαίνεται μόνο όταν το πεδίο έχει εστίαση
        return true
    }

    private fun replaceCurrent() {
        val query = binding.findInput.text.toString()
        if (query.isEmpty()) return
        val start = minOf(editor.selectionStart, editor.selectionEnd)
        val end = maxOf(editor.selectionStart, editor.selectionEnd)
        if (start >= 0 && editor.text.substring(start, end).equals(query, !binding.matchCase.isChecked)) {
            editor.text.replace(start, end, binding.replaceInput.text.toString())
        }
        findNext(forward = true)
    }

    private fun replaceAll() {
        val query = binding.findInput.text.toString()
        if (query.isEmpty()) return
        val replacement = binding.replaceInput.text.toString()
        val ignoreCase = !binding.matchCase.isChecked
        val original = editor.text.toString()

        val result = StringBuilder()
        var from = 0
        var count = 0
        while (true) {
            val i = original.indexOf(query, from, ignoreCase)
            if (i < 0) break
            result.append(original, from, i).append(replacement)
            from = i + query.length
            count++
        }
        if (count == 0) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
            return
        }
        result.append(original, from, original.length)
        val cursor = editor.selectionStart
        editor.text.replace(0, original.length, result)
        editor.setSelection(cursor.coerceIn(0, editor.text.length))
        Toast.makeText(this, getString(R.string.replaced_count, count), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- Βοηθητικά

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS = "editor"
        const val KEY_SIZE = "text_size"
        const val KEY_MONO = "monospace"
        const val KEY_WRAP = "word_wrap"
        const val KEY_NIGHT = "night_mode"
        const val STATE_URI = "state_uri"
        const val STATE_NAME = "state_name"
        const val STATE_MODIFIED = "state_modified"
        const val DEFAULT_SIZE = 16f
        const val MIN_SIZE = 10f
        const val MAX_SIZE = 40f
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}
