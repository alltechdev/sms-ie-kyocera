/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.github.tmo1.sms_ie

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/** Select the keypad document UI when the manufacturer softkey API is available. */
private fun nativeLocation(context: Context, intent: Intent): Intent {
    if (runCatching { Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide") }.isSuccess) {
        return Intent(intent).setClass(context, KyoceraFilePicker::class.java)
    }
    return intent
}

class KyoceraCreateDocument(type: String) : ActivityResultContracts.CreateDocument(type) {
    override fun createIntent(context: Context, input: String) = nativeLocation(context, super.createIntent(context, input))
}

class KyoceraOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>) = nativeLocation(context, super.createIntent(context, input))
}

class KyoceraOpenTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = super.createIntent(context, input)
        return if (input == null) nativeLocation(context, intent) else intent
    }
}
