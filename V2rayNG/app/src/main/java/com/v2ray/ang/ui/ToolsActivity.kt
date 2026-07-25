package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.anonymouskeys.monstervpn.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.helper.MmkvPreferenceDataStore

/**
 * Dragon/Neko tools hub.
 *
 * Fragmentation is handled by Xray stream settings, while DPI bypass is handled
 * by the bundled ByeDPI runtime. Both sets of controls use the same MMKV keys as
 * the main settings screen, so there is only one source of truth.
 */
class ToolsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            R.layout.activity_tools,
            showHomeAsUp = true,
            title = getString(R.string.nav_tools),
        )
    }

    class ToolsFragment : PreferenceFragmentCompat() {
        private val fragmentEnabled by lazy {
            findPreference<CheckBoxPreference>(AppConfig.PREF_FRAGMENT_ENABLED)
        }
        private val fragmentPackets by lazy {
            findPreference<ListPreference>(AppConfig.PREF_FRAGMENT_PACKETS)
        }
        private val fragmentLength by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_LENGTH)
        }
        private val fragmentInterval by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_INTERVAL)
        }
        private val fragmentMaxSplit by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_FRAGMENT_MAXSPLIT)
        }

        private val dpiEnabled by lazy {
            findPreference<SwitchPreferenceCompat>(AppConfig.PREF_DPI_ENABLED)
        }
        private val dpiStrategy by lazy {
            findPreference<ListPreference>(AppConfig.PREF_DPI_STRATEGY)
        }
        private val dpiSplitPosition by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_DPI_SPLIT_POSITION)
        }
        private val dpiFakeTtl by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_DPI_FAKE_TTL)
        }
        private val dpiFakeCount by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_DPI_FAKE_COUNT)
        }
        private val dpiDelayMs by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_DPI_DELAY_MS)
        }
        private val dpiPortsOnly by lazy {
            findPreference<CheckBoxPreference>(AppConfig.PREF_DPI_PORTS_80_443_ONLY)
        }
        private val dpiExpertArgs by lazy {
            findPreference<EditTextPreference>(AppConfig.PREF_DPI_EXPERT_ARGS)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = MmkvPreferenceDataStore()
            setPreferencesFromResource(R.xml.pref_tools, rootKey)

            bindSummary(fragmentPackets)
            bindSummary(fragmentLength)
            bindSummary(fragmentInterval)
            bindSummary(fragmentMaxSplit)
            bindSummary(dpiStrategy)
            bindSummary(dpiSplitPosition)
            bindSummary(dpiFakeTtl)
            bindSummary(dpiFakeCount)
            bindSummary(dpiDelayMs)

            fragmentEnabled?.setOnPreferenceChangeListener { _, newValue ->
                updateFragmentState(newValue as Boolean)
                SettingsChangeManager.makeRestartService()
                true
            }

            listOf(
                fragmentPackets,
                fragmentLength,
                fragmentInterval,
                fragmentMaxSplit,
                dpiEnabled,
                dpiStrategy,
                dpiSplitPosition,
                dpiFakeTtl,
                dpiFakeCount,
                dpiDelayMs,
                dpiPortsOnly,
                dpiExpertArgs,
            ).forEach { preference ->
                preference?.setOnPreferenceChangeListener { pref, newValue ->
                    updateSummary(pref, newValue)
                    SettingsChangeManager.makeRestartService()
                    true
                }
            }

            findPreference<Preference>(KEY_BACKUP)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), BackupActivity::class.java))
                true
            }

            updateFragmentState(
                MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
            )
        }

        private fun bindSummary(preference: Preference?) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(preference.value)
                    if (index >= 0) preference.summary = preference.entries[index]
                }
                is EditTextPreference -> {
                    preference.summary = preference.text.orEmpty()
                }
            }
        }

        private fun updateSummary(preference: Preference, newValue: Any?) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(newValue.toString())
                    preference.summary = if (index >= 0) {
                        preference.entries[index]
                    } else {
                        newValue.toString()
                    }
                }
                is EditTextPreference -> preference.summary = newValue.toString()
            }
        }

        private fun updateFragmentState(enabled: Boolean) {
            fragmentPackets?.isEnabled = enabled
            fragmentLength?.isEnabled = enabled
            fragmentInterval?.isEnabled = enabled
            fragmentMaxSplit?.isEnabled = enabled
        }

        companion object {
            private const val KEY_BACKUP = "tools_backup_restore"
        }
    }
}
