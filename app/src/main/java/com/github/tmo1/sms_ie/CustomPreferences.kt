/*
 * SMS Import / Export: a simple Android app for importing and exporting SMS and MMS messages,
 * call logs, contacts, and blocked numbers from and to JSON / NDJSON files.
 *
 * Copyright (c) 2021-2022,2026 Thomas More
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

import android.content.Context
import android.text.format.DateUtils
import android.util.AttributeSet
import android.preference.Preference
import android.preference.PreferenceManager
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import java.util.Formatter
import java.util.Locale

class TimePickerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    fun getPersistedMinutesAfterMidnight(): Int = getPersistedInt(DEFAULT_MINUTES_AFTER_MIDNIGHT)

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        if (!restorePersistedValue) persistInt((defaultValue as? Int) ?: DEFAULT_MINUTES_AFTER_MIDNIGHT)
        summary = minutesAfterMidnightToHourlyTime(context, getPersistedMinutesAfterMidnight())
    }

    override fun onClick() {
        val host = context as FragmentActivity
        if (host.supportFragmentManager.findFragmentByTag("native_time_picker") != null) return
        NativeTimePickerFragment().apply {
            arguments = Bundle().apply {
                putString("key", key)
                putString("title", title.toString())
                putInt("minutes", getPersistedMinutesAfterMidnight())
            }
        }.show(host.supportFragmentManager, "native_time_picker")
    }

    companion object {
        const val DEFAULT_MINUTES_AFTER_MIDNIGHT = 120
    }
}

class NativeTimePickerFragment : DialogFragment() {
    override fun onCreateDialog(state: Bundle?): android.app.Dialog {
        val context = requireContext()
        val arguments = requireArguments()
        val key = requireNotNull(arguments.getString("key"))
        val minutes = arguments.getInt("minutes", TimePickerPreference.DEFAULT_MINUTES_AFTER_MIDNIGHT)
        return TimePickerDialog(context, { _, hour, minute ->
            PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, hour * 60 + minute).apply()
            scheduleAutomaticExport(context, true)
        }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(context)).apply {
            setTitle(arguments.getString("title"))
        }
    }
}

fun minutesAfterMidnightToHourlyTime(context: Context, minutesAfterMidnight: Int): CharSequence {
    // https://stackoverflow.com/questions/38594746/android-dateutils-formatdatetime-wrong-timezone/38596172
    val stringBuilder = StringBuilder(50)
    val formatter = Formatter(stringBuilder, Locale.getDefault())
    val millisAfterMidnight = minutesAfterMidnight.toLong() * 60000
    return DateUtils.formatDateRange(
        context,
        formatter,
        millisAfterMidnight,
        millisAfterMidnight,
        DateUtils.FORMAT_SHOW_TIME,
        "UTC"
    ).toString()
}
