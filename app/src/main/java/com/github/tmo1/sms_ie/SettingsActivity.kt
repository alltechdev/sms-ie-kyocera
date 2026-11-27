/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2023,2026 Thomas More
 *
 * This file is part of SMS Import / Export.
 *
 * SMS Import / Export is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMS Import / Export is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SMS Import / Export.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.tmo1.sms_ie

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceGroup
import android.preference.SwitchPreference

const val REQUEST_EXPORT_FOLDER = 4
const val EXPORT_DIR = "export_dir"
const val DISABLE_BATTERY_OPTIMIZATIONS = "disable_battery_optimizations"

class SettingsActivity : KyoceraActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            fragmentManager.beginTransaction().replace(R.id.settings, SettingsFragment())
                .commit()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        //prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setTitle(R.string.settings)

        supportFragmentManager.setFragmentResultListener(
            "setPassphrase", this
        ) { requestKey, bundle ->
            bundle.getString("passphrase")?.let {
                val passphraseManager = PassphraseManager("passphrase_key", this)
                passphraseManager.storePassphrase(it)
            }
        }
    }

    class SettingsFragment : PreferenceFragment() {
        private fun requireContext(): android.content.Context = checkNotNull(activity)

        // https://stackoverflow.com/questions/70803830/updating-a-preference-summary-in-android-when-the-user-sets-it
        private val prefs by lazy { preferenceManager.sharedPreferences }
        private val targetDirPreference: Preference by lazy {
            findPreference(EXPORT_DIR) ?: error("Missing export directory preference!")
        }
        private val disableBattOptPreference: SwitchPreference by lazy {
            findNativePreference<SwitchPreference>(DISABLE_BATTERY_OPTIMIZATIONS)
                ?: error("Missing disable battery optimizations preference!")
        }
        private val requestDisableBattOpt = object {
            fun launch(intent: Intent) { startActivityForResult(intent, 105) }
        }
        private val requestPostNotification = object {
            fun launch(permission: String) { requestPermissions(arrayOf(permission), 106) }
        }
        private inline fun <reified T : Preference> findNativePreference(key: String): T? = findPreference(key) as? T

        // We need to store a reference to the preference change listener here (and not just inside
        // `onCreatePreferences()`) in order to prevent it from being garbage collected:
        // https://stackoverflow.com/questions/2542938/sharedpreferences-onsharedpreferencechangelistener-not-being-called-consistently
        // https://developer.android.com/develop/ui/views/components/settings/use-saved-values#change-listener
        // https://developer.android.com/reference/android/content/SharedPreferences.html#registerOnSharedPreferenceChangeListener(android.content.SharedPreferences.OnSharedPreferenceChangeListener)
        private lateinit var prefListener: SharedPreferences.OnSharedPreferenceChangeListener

        @SuppressLint("BatteryLife")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.root_preferences)
            fun nativeRows(group: PreferenceGroup) {
                for (index in 0 until group.preferenceCount) {
                    val item = group.getPreference(index)
                    item.isIconSpaceReserved = false
                    if (item is TimePickerPreference) item.summary = minutesAfterMidnightToHourlyTime(requireContext(), item.getPersistedMinutesAfterMidnight())
                    item.isSingleLineTitle = true
                    if (item is EditTextPreference) {
                        item.summary = item.text
                        item.setOnPreferenceChangeListener { _, value -> item.summary = value.toString(); true }
                    }
                    if (item is PreferenceGroup) nativeRows(item)
                }
            }
            nativeRows(preferenceScreen)

            // The LazySodium Android binding requires API level >= 21.
            // https://github.com/terl/lazysodium-android/blob/76e18548a0af285215f536c0d6d17369ac598311/app/build.gradle#L42
            if (SDK_INT < 21) {
                val encryption = findNativePreference<PreferenceGroup>("encryption_preference_category")
                encryption?.isEnabled = false
                encryption?.summary = "Encryption requires API level >= 21"
            }

            // The time picker is somehow calling 'android.widget.TimePicker.setHour', which was
            // added in API level 23, and 'Intent.ACTION_OPEN_DOCUMENT_TREE', which was added in
            // API level 21, so we disable scheduled export for API < 23.
            // https://stackoverflow.com/questions/32297765/android-timepicker-methods-being-stubs
            // https://developer.android.com/reference/android/content/Intent#ACTION_OPEN_DOCUMENT_TREE

            if (SDK_INT < 23) {
                val scheduledExport =
                    findNativePreference<PreferenceGroup>("scheduled_export_preference_category")
                scheduledExport?.isEnabled = false
                scheduledExport?.summary = "Scheduled export requires API level >= 23"
            } else {
                targetDirPreference.setOnPreferenceClickListener {
                    val intent = KyoceraOpenTree().createIntent(requireContext(), null)
                    startActivityForResult(intent, REQUEST_EXPORT_FOLDER)
                    true
                }
                updateExportDirPreferenceSummary()
            }

            findNativePreference<SwitchPreference>("save_logcat")!!.summary =
                getString(R.string.pref_save_logcat_summary, logcatFile(requireContext()))

            if (SDK_INT >= Build.VERSION_CODES.M) {
                disableBattOptPreference.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true) {
                        requestDisableBattOpt.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.fromParts("package", requireContext().packageName, null),
                            )
                        )
                    } else {
                        // There is no API to request battery optimizations to be re-enabled, so
                        // send the user to Android's Settings page for them to do it manually.
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    false
                }
            }

            // see: https://stackoverflow.com/questions/26242581/call-method-after-changing-preferences-in-android
            // https://stackoverflow.com/questions/7020446/android-registeronsharedpreferencechangelistener-causes-crash-in-a-custom-view#7021068
            // https://stackoverflow.com/questions/66449883/kotlin-onsharedpreferencechangelistener
            prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->

                when (key) {

                    "schedule_export" -> {
                        context?.let { scheduleAutomaticExport(it, true) }
                        if (SDK_INT >= 33 && sharedPrefs.getBoolean(key, false)) {
                            context?.let {
                                if (ContextCompat.checkSelfPermission(
                                        it, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestPostNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    }

                    "export_interval_days" -> context?.let {
                        scheduleAutomaticExport(it, true)
                    }

                    "last_successful_scheduled_export", "next_scheduled_export" -> {
                        val pref = findNativePreference<Preference>(key)
                        val prefs = prefs
                        prefs?.let {
                            val date = prefs.getLong(key, 0)
                            context?.let {
                                pref?.summary = if (date > 0) formatDate(it, date) else "<None>"
                            }
                        }
                    }
                }
            }

            prefs?.registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onStart() {
            super.onStart()

            // Changing the option does not reload the activity.
            if (SDK_INT >= Build.VERSION_CODES.M) updateBatteryOptimizationState()
        }

        override fun onResume() {
            super.onResume()

            val prefs = prefs
            prefs?.let {

                // https://developer.android.com/develop/ui/views/components/settings/use-saved-values#change-listener
                it.registerOnSharedPreferenceChangeListener(prefListener)

                var pref = findNativePreference<Preference>("last_successful_scheduled_export")
                var date = prefs.getLong("last_successful_scheduled_export", 0)
                pref?.summary = if (date > 0) formatDate(requireContext(), date) else "<None>"
                pref = findPreference("next_scheduled_export")
                date = prefs.getLong("next_scheduled_export", 0)
                pref?.summary = if (date > 0) formatDate(requireContext(), date) else "<None>"

                findNativePreference<EditTextPreference>("export_interval_days")?.let {pref ->

                    pref.editText.inputType = InputType.TYPE_CLASS_NUMBER
                    pref.editText.hint = "Enter a number >= 1"

                    // Ensure that export interval >= 1, since if it's 0 the app will go into an
                    // infinite loop of scheduling and running exports ...
                    pref.setOnPreferenceChangeListener { _, newValue ->
                        val interval = (newValue as? String)?.toIntOrNull()
                        // 'True' means that the new value is accepted and 'False' means that it's
                        // rejected.
                        val valid = interval != null && interval >= 1
                        if (valid) pref.summary = newValue.toString()
                        valid
                    }
                }
            }
        }

        override fun onPause() {
            super.onPause()

            // https://developer.android.com/develop/ui/views/components/settings/use-saved-values#change-listener
            prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        }

        // from: https://old.black/2020/09/18/building-custom-timepicker-dialog-preference-in-android-kotlin/
        override fun onPreferenceTreeClick(screen: android.preference.PreferenceScreen, preference: Preference): Boolean {
            if (preference is android.preference.DialogPreference) {
                view?.post { (preference.dialog as? android.app.AlertDialog)?.let { bindNativeDialog(it) } }
            }
            return super.onPreferenceTreeClick(screen, preference)
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
            super.onActivityResult(requestCode, resultCode, intent)
            if (requestCode == 105) { updateBatteryOptimizationState(); return }
            // from: https://stackoverflow.com/questions/34331956/trying-to-takepersistableuripermission-fails-for-custom-documentsprovider-via
            if (requestCode == REQUEST_EXPORT_FOLDER && resultCode == RESULT_OK && intent != null) {
                val treeUri = intent.data
                //Log.v(LOG_TAG, "Tree acquired: ${Uri.decode(treeUri.toString())}")
                if (treeUri != null) {
                    // TODO: we should probably call releasePersistableUriPermission on the current URI
                    context?.contentResolver?.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )/*val documentTree = activity?.let { DocumentFile.fromTreeUri(it, treeUri) }
                    val file = documentTree?.createFile("text/plain", "sms-ie.test")
                    val fileUri = file?.uri
                    if (fileUri != null) {
//                  Log.v(LOG_TAG, "File acquired: $fileUri")
                        context?.contentResolver?.openOutputStream(fileUri, "wt").use { outputStream ->
                            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                                writer.write("It works!")
                            }
                        }
                    }*/
                }
                prefs?.edit {
                    putString(EXPORT_DIR, treeUri.toString())
                }
                updateExportDirPreferenceSummary()
                // for worker testing: https://developer.android.com/topic/libraries/architecture/workmanager/basics#samples
                /*val exportRequest: WorkRequest =
                    OneTimeWorkRequestBuilder<ExportWorker>()
                        .addTag(ImportExportWorker.TAG_AUTOMATIC_EXPORT)
                        .build()
                activity?.let {
                    WorkManager
                        .getInstance(it)
                        .enqueue(exportRequest)
                }*/
            } else {
                Log.e(
                    LOG_TAG,
                    "Tree acquisition failed:\trequestCode: $requestCode\tresultCode: $resultCode"
                )
            }
        }

        private fun updateExportDirPreferenceSummary() {
            targetDirPreference.summary = Uri.decode(prefs?.getString(EXPORT_DIR, ""))
        }

        private fun updateBatteryOptimizationState() {
            if (SDK_INT >= Build.VERSION_CODES.M) {
                val context = requireContext()
                val pm: PowerManager = context.getSystemService(POWER_SERVICE) as PowerManager
                disableBattOptPreference.isChecked =
                    pm.isIgnoringBatteryOptimizations(context.packageName)
            } else disableBattOptPreference.isEnabled = false
        }
    }

    fun onPassphraseButtonClick(view: View) {
        PassphraseEntryFragment().show(supportFragmentManager, "passphrase_entry")
    }

}
