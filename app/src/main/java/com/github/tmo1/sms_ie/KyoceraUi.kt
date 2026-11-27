/* SPDX-License-Identifier: GPL-3.0-or-later
 * Native keypad UI using Kyocera framework controls. */
package com.github.tmo1.sms_ie

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.view.Window
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import java.lang.reflect.Proxy

class KyoceraSoftkeys(window: Window, center: () -> Unit, left: () -> Unit, right: () -> Unit) {
    private val type = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide")
    private val guide = type.getMethod("getSoftkeyGuide", Window::class.java).invoke(null, window)
        ?: error("Kyocera softkey guide unavailable")
    private val listeners = arrayListOf<Any>()
    init {
        val listener = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide\$SoftkeyEventListener")
        listOf(center, left, right).forEachIndexed { index, action ->
            val proxy = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { self, method, args ->
                when (method.name) {
                    "onSoftkeySelected" -> { action(); null }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "SmsIeKyoceraSoftkey"
                    else -> null
                }
            }
            listeners.add(proxy)
            type.getMethod("setSoftkeyEventListener", Int::class.javaPrimitiveType, listener).invoke(guide, index, proxy)
        }
    }
    fun labels(center: String, left: String, right: String, priority: Int = 2) {
        listOf(center, left, right).forEachIndexed { index, label ->
            val listener = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide\$SoftkeyEventListener")
            type.getMethod("setSoftkeyEventListener", Int::class.javaPrimitiveType, listener)
                .invoke(guide, index, if (label == "@SK_AUTO_ENTER" || label.isEmpty()) null else listeners[index])
            type.getMethod("setPriority", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(guide, index, if (label.isEmpty()) 0 else priority)
            type.getMethod("setText", Int::class.javaPrimitiveType, CharSequence::class.java).invoke(guide, index, label)
            type.getMethod("setEnabled", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(guide, index, label.isNotEmpty())
        }
        type.getMethod("invalidate").invoke(guide)
    }
}

open class KyoceraActivity : FragmentActivity() {
    private var guide: KyoceraSoftkeys? = null
    protected open fun nativeRightLabel() = ""
    protected open fun nativeRight() { openOptionsMenu() }
    protected open fun nativeLeftLabel() = "Back"
    protected open fun nativeLeft() { onBackPressedDispatcher.onBackPressed() }
    val supportActionBar get() = actionBar

    protected fun activateNativeSelection(): Boolean {
        var focus = currentFocus
        if (focus is EditText) return false
        while (focus != null) {
            if (focus is ListView && focus.selectedItemPosition >= 0) {
                return focus.performItemClick(focus.selectedView, focus.selectedItemPosition, focus.selectedItemId)
            }
            if (focus.isClickable && focus.isEnabled && focus.performClick()) return true
            focus = focus.parent as? View
        }
        return false
    }

    fun refreshNativeSoftkeys() {
        try {
            if (guide == null) guide = KyoceraSoftkeys(window, { activateNativeSelection() }, { nativeLeft() }, { nativeRight() })
            guide?.labels(if (currentFocus is EditText) "@SK_AUTO_ENTER" else "Select", nativeLeftLabel(), nativeRightLabel())
        } catch (_: ReflectiveOperationException) { guide = null }
    }
    fun hasNativeSoftkeys() = guide != null
    override fun onResume() { super.onResume(); refreshNativeSoftkeys() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshNativeSoftkeys()
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && activateNativeSelection()) return true
            if (code == KeyEvent.KEYCODE_SOFT_LEFT) { nativeLeft(); return true }
            if (code == KeyEvent.KEYCODE_SOFT_RIGHT || code == KeyEvent.KEYCODE_MENU) {
                if (nativeRightLabel().isNotEmpty()) { nativeRight(); return true }
            }
        }
        return super.onKeyDown(code, event)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { nativeLeft(); return true }
        return super.onOptionsItemSelected(item)
    }
}

open class KyoceraDialogFragment : DialogFragment() {
    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.let { bindNativeDialog(it) }
    }
}

fun bindNativeDialog(dialog: AlertDialog) {
    try {
        fun positive() = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun cancel() {
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            if (!negative?.text.isNullOrEmpty()) negative.performClick() else dialog.cancel()
        }
        val guide = KyoceraSoftkeys(dialog.window!!, {
            val focus = dialog.currentFocus
            if (focus is EditText) positive()?.performClick()
            else if (focus is ListView && focus.selectedItemPosition >= 0)
                focus.performItemClick(focus.selectedView, focus.selectedItemPosition, focus.selectedItemId)
            else focus?.performClick()
        }, { cancel() }, {
            if (dialog.currentFocus is EditText) cancel() else positive()?.performClick()
        })
        fun refresh() {
            val editing = dialog.currentFocus is EditText
            val label = positive()?.text?.toString().orEmpty()
            guide.labels(if (editing) label.ifEmpty { "OK" } else "Select",
                if (editing) "" else "Cancel", if (editing) "Cancel" else label,
                if (editing) 4 else 3)
        }
        dialog.window!!.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> refresh() }
        refresh()
    } catch (_: ReflectiveOperationException) { /* Standard Android keys remain functional. */ }
}

fun applyNativeListAppearance(list: ListView) {
    val divider = list.resources.getIdentifier("fp_list_divider", "drawable", "android")
    if (divider != 0) {
        list.divider = list.context.getDrawable(divider)
        list.dividerHeight = list.divider?.intrinsicHeight?.coerceAtLeast(1) ?: 1
    }
}

class NativeAction(val id: Int, val label: String, private val changed: () -> Unit) {
    var isEnabled = true
        set(value) { if (field != value) { field = value; changed() } }
    private var action: ((View?) -> Unit)? = null
    fun setOnClickListener(listener: (View?) -> Unit) { action = listener }
    fun invoke(view: View?) { if (isEnabled) action?.invoke(view) }
    override fun toString() = label
}

class KyoceraMainUi(activity: KyoceraActivity) {
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val list = ListView(activity)
    private val actions = arrayListOf<NativeAction>()
    private val adapter = object : ArrayAdapter<NativeAction>(activity, android.R.layout.simple_list_item_1, actions) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).apply {
                (this as TextView).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                }
            }
        override fun hasStableIds() = true
        override fun getItemId(position: Int) = actions[position].id.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = actions[position].isEnabled
    }
    init {
        val labels = listOf(
            R.id.export_messages_button to R.string.export_messages_button_label,
            R.id.import_messages_button to R.string.import_messages_button_label,
            R.id.export_call_log_button to R.string.export_call_log_button_label,
            R.id.import_call_log_button to R.string.import_call_log_button_label,
            R.id.export_contacts_button to R.string.export_contacts,
            R.id.import_contacts_button to R.string.import_contacts,
            R.id.export_blocked_numbers_button to R.string.export_blocked_numbers,
            R.id.import_blocked_numbers_button to R.string.import_blocked_numbers,
            R.id.count_messages_button to R.string.count_messages,
            R.id.wipe_all_messages_button to R.string.wipe_messages,
        )
        labels.forEach { (id, label) -> actions.add(NativeAction(id, activity.getString(label)) { adapter.notifyDataSetChanged() }) }
        applyNativeListAppearance(list)
        list.adapter = adapter
        list.setOnItemClickListener { _, view, position, _ -> actions[position].invoke(view) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun text(id: Int, label: CharSequence, gone: Boolean = false) = TextView(activity).apply {
            this.id = id; text = label; textSize = 14f
            setPadding(8, 4, 8, 4)
            if (gone) visibility = View.GONE
            footer.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        footer.addView(NativeOperationStatus(activity).apply { id = R.id.status_report; textSize = 14f }, LinearLayout.LayoutParams(-1, -2))
        footer.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.progressBar; visibility = View.GONE
        }, LinearLayout.LayoutParams(-1, -2))
        footer.addView(Button(activity).apply { id = R.id.cancel_button; setText(android.R.string.cancel); visibility = View.GONE }, LinearLayout.LayoutParams(-1, -2))
        text(R.id.default_sms_app_warning, "", true)
        footer.addView(Button(activity).apply { id = R.id.set_default_sms_app_button; setText(R.string.set_default_sms_app); visibility = View.GONE }, LinearLayout.LayoutParams(-1, -2))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        list.requestFocus()
        list.setSelection(0)
    }
    fun action(id: Int) = actions.single { it.id == id }
}

private class NativeOperationStatus(context: android.content.Context) : TextView(context) {
    init { visibility = View.GONE }
    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        visibility = if (text.isNullOrBlank() || text.toString() == context.getString(R.string.ready)) View.GONE else View.VISIBLE
    }
}
