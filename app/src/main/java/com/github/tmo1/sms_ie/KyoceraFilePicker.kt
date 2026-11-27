/* SPDX-License-Identifier: GPL-3.0-or-later
 * Native keypad document picker. Uses the framework DocumentsProvider;
 * never accesses block devices, requires no root shell or broad filesystem API. */
package com.github.tmo1.sms_ie

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.util.ArrayDeque
import java.util.concurrent.Executors

class KyoceraFilePicker : KyoceraActivity() {
    private data class Entry(val id: String, val name: String, val directory: Boolean, val flags: Int = 0) {
        override fun toString() = name
    }
    private val authority = "com.atd.kyocera.documents"
    private val executor = Executors.newSingleThreadExecutor()
    private val entries = arrayListOf<Entry>()
    private val parents = ArrayDeque<Entry>()
    private lateinit var list: ListView
    private lateinit var location: TextView
    private lateinit var adapter: ArrayAdapter<Entry>
    private var directory: Entry? = null
    private var filename = "backup.zip"
    private var generation = 0
    private var signal: CancellationSignal? = null
    private var busy = false
    private val create get() = intent.action == Intent.ACTION_CREATE_DOCUMENT
    private val tree get() = intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = if (create) "Save backup" else if (tree) "Choose folder" else "Open backup"
        filename = state?.getString("filename") ?: intent.getStringExtra(Intent.EXTRA_TITLE) ?: "backup.zip"
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        location = TextView(this).apply { textSize = 14f; setPadding(8, 4, 8, 4) }
        page.addView(location, LinearLayout.LayoutParams(-1, -2))
        list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> select(entries[position]) }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { navigateBack() }
        })
        val provider = packageManager.resolveContentProvider(authority, 0)
        if (provider?.packageName != "com.atd.kyocera.documents" ||
            packageManager.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, provider.packageName) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission("com.github.tmo1.sms_ie.permission.DOCUMENTS") != PackageManager.PERMISSION_GRANTED) {
            failure("File access service is unavailable.")
            return
        }
        loadRoots()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("filename", filename)
        super.onSaveInstanceState(out)
    }

    override fun nativeLeftLabel() = if (directory == null) "Cancel" else "Back"
    override fun nativeLeft() { navigateBack() }
    override fun nativeRightLabel() = if (busy || directory == null) "" else if (create) "Save" else if (tree) "Select" else ""
    override fun nativeRight() {
        if (busy || directory == null) return
        if (create) save() else if (tree) finishWith(DocumentsContract.buildTreeDocumentUri(authority, directory!!.id), true)
    }

    private fun begin(): Pair<Int, CancellationSignal> {
        signal?.cancel()
        signal = CancellationSignal()
        generation++
        busy = true
        location.text = "Loading…"
        list.isEnabled = false
        refreshNativeSoftkeys()
        return generation to signal!!
    }

    private fun display(ticket: Int, rows: List<Entry>, path: String) {
        if (ticket != generation || isFinishing || isDestroyed) return
        entries.clear()
        if (create) entries.add(Entry("@name", "Name: $filename", false))
        entries.addAll(rows)
        adapter.notifyDataSetChanged()
        location.text = path
        busy = false
        list.isEnabled = true
        list.requestFocus()
        list.setSelection(if (create && rows.isNotEmpty()) 1 else 0)
        refreshNativeSoftkeys()
    }

    private fun loadRoots() {
        directory = null
        parents.clear()
        val (ticket, cancel) = begin()
        executor.execute {
            try {
                val rows = arrayListOf<Entry>()
                val columns = arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE)
                contentResolver.query(DocumentsContract.buildRootsUri(authority), columns, null, null, null, cancel).use { cursor ->
                    if (cursor != null) while (cursor.moveToNext()) {
                        rows.add(Entry(cursor.getString(0), cursor.getString(1), true))
                    }
                }
                runOnUiThread { display(ticket, rows, "Choose storage") }
            } catch (error: Exception) { report(ticket, error) }
        }
    }

    private fun loadDirectory(entry: Entry, push: Boolean = true) {
        if (push && directory != null) parents.addLast(directory!!)
        directory = entry
        val (ticket, cancel) = begin()
        executor.execute {
            try {
                val rows = arrayListOf<Entry>()
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS)
                contentResolver.query(DocumentsContract.buildChildDocumentsUri(authority, entry.id), columns, null, null, null, cancel).use { cursor ->
                    if (cursor != null) while (cursor.moveToNext()) {
                        val dir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (dir || (!create && !tree)) rows.add(Entry(cursor.getString(0), cursor.getString(1), dir, cursor.getInt(3)))
                    }
                }
                rows.sortWith(compareBy<Entry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                runOnUiThread { display(ticket, rows, entry.id.replace(':', '/')) }
            } catch (error: Exception) { report(ticket, error) }
        }
    }

    private fun select(entry: Entry) {
        if (busy) return
        if (entry.id == "@name") { editFilename(); return }
        if (entry.directory) loadDirectory(entry)
        else if (!create && !tree) finishWith(DocumentsContract.buildDocumentUri(authority, entry.id))
    }

    private fun editFilename() {
        val edit = EditText(this).apply {
            setText(filename)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val dialog = AlertDialog.Builder(this).setTitle("File name").setView(edit)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edit.text.toString().trim()
                if (name.isEmpty() || name in listOf(".", "..") || name.any { it == '/' || it == '\\' || it.code < 32 } || name.toByteArray(Charsets.UTF_8).size > 255) {
                    edit.error = "Enter a valid file name"
                } else {
                    filename = name
                    val index = entries.indexOfFirst { it.id == "@name" }
                    if (index >= 0) entries[index] = Entry("@name", "Name: $filename", false)
                    adapter.notifyDataSetChanged()
                    dialog.dismiss()
                }
            }
            bindNativeDialog(dialog)
        }
        dialog.show()
    }

    private fun save() {
        val parent = directory ?: return
        val (ticket, _) = begin()
        location.text = "Creating backup file…"
        executor.execute {
            var created: Uri? = null
            try {
                created = DocumentsContract.createDocument(contentResolver,
                    DocumentsContract.buildDocumentUri(authority, parent.id), intent.type ?: "application/octet-stream", filename)
                    ?: error("Could not create the backup file")
                val result = created
                runOnUiThread {
                    if (ticket == generation && !isFinishing && !isDestroyed) finishWith(result)
                    else Thread { runCatching { DocumentsContract.deleteDocument(contentResolver, result) } }.start()
                }
            } catch (error: Exception) {
                if (created != null) runCatching { DocumentsContract.deleteDocument(contentResolver, created) }
                report(ticket, error)
            }
        }
    }

    private fun finishWith(uri: Uri, prefix: Boolean = false) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or (if (prefix) Intent.FLAG_GRANT_PREFIX_URI_PERMISSION else 0)
        setResult(RESULT_OK, Intent().setData(uri).addFlags(flags))
        finish()
    }

    private fun navigateBack() {
        signal?.cancel()
        generation++
        if (parents.isNotEmpty()) loadDirectory(parents.removeLast(), false)
        else if (directory != null) loadRoots()
        else { setResult(RESULT_CANCELED); finish() }
    }

    private fun report(ticket: Int, error: Exception) {
        runOnUiThread {
            if (ticket == generation && !isFinishing && !isDestroyed) {
                busy = false
                list.isEnabled = true
                failure(error.message ?: "Could not open this location")
            }
        }
    }

    private fun failure(message: String) {
        location.text = message
        val dialog = AlertDialog.Builder(this).setTitle("File access").setMessage(message)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { bindNativeDialog(dialog) }
        dialog.show()
        refreshNativeSoftkeys()
    }

    override fun onDestroy() {
        signal?.cancel()
        generation++
        executor.shutdown()
        super.onDestroy()
    }
}
