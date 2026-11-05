/*
 * Copyright (C) 2018-2025 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.ui.settings.pages;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.InputFilterRange;
import org.proninyaroslav.libretorrent.core.RepositoryHelper;
import org.proninyaroslav.libretorrent.core.model.remote.RemoteControlServer;
import org.proninyaroslav.libretorrent.core.settings.SettingsRepository;
import org.proninyaroslav.libretorrent.ui.settings.CustomPreferenceFragment;

public class StreamingSettingsFragment extends CustomPreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private SettingsRepository pref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pref = RepositoryHelper.getSettingsRepository(requireContext().getApplicationContext());

        String keyEnable = getString(R.string.pref_key_streaming_enable);
        SwitchPreferenceCompat enable = findPreference(keyEnable);
        if (enable != null) {
            enable.setChecked(pref.enableStreaming());
            bindOnPreferenceChangeListener(enable);
        }

        String keyHostname = getString(R.string.pref_key_streaming_hostname);
        EditTextPreference hostname = findPreference(keyHostname);
        if (hostname != null) {
            String addressValue = pref.streamingHostname();
            hostname.setText(addressValue);
            hostname.setSummary(addressValue);
            bindOnPreferenceChangeListener(hostname);
        }

        String keyPort = getString(R.string.pref_key_streaming_port);
        EditTextPreference port = findPreference(keyPort);
        if (port != null) {
            InputFilter[] portFilter = new InputFilter[]{InputFilterRange.PORT_FILTER};
            int portNumber = pref.streamingPort();
            String portValue = Integer.toString(portNumber);
            port.setOnBindEditTextListener((editText) -> editText.setFilters(portFilter));
            port.setSummary(portValue);
            port.setText(portValue);
            bindOnPreferenceChangeListener(port);
        }

        String keyRemoteEnable = getString(R.string.pref_key_remote_enable);
        SwitchPreferenceCompat remoteEnable = findPreference(keyRemoteEnable);
        if (remoteEnable != null) {
            remoteEnable.setChecked(pref.enableRemoteControl());
            bindOnPreferenceChangeListener(remoteEnable);
        }

        String keyRemotePassword = getString(R.string.pref_key_remote_password);
        EditTextPreference remotePassword = findPreference(keyRemotePassword);
        if (remotePassword != null) {
            String value = pref.remoteControlPassword();
            remotePassword.setText(value);
            remotePassword.setSummary(value);
            bindOnPreferenceChangeListener(remotePassword);
        }

        String keyRemotePort = getString(R.string.pref_key_remote_port);
        EditTextPreference remotePort = findPreference(keyRemotePort);
        if (remotePort != null) {
            InputFilter[] portFilter = new InputFilter[]{InputFilterRange.PORT_FILTER};
            String portValue = Integer.toString(pref.remoteControlPort());
            remotePort.setOnBindEditTextListener((editText) -> editText.setFilters(portFilter));
            remotePort.setSummary(portValue);
            remotePort.setText(portValue);
            bindOnPreferenceChangeListener(remotePort);
        }

        updateRemoteAddress(pref.remoteControlPort());
    }

    @Override
    public void onResume() {
        super.onResume();
        /* The IP address may have changed (e.g. Wi-Fi reconnected) */
        updateRemoteAddress(pref.remoteControlPort());
    }

    private void updateRemoteAddress(int port) {
        Preference address = findPreference(getString(R.string.pref_key_remote_address));
        if (address == null)
            return;
        java.util.List<String> urls = RemoteControlServer.getLocalUrls(port);
        String value = urls.isEmpty()
                ? getString(R.string.pref_remote_address_none)
                : TextUtils.join("\n", urls);
        address.setSummary(getString(R.string.pref_remote_address_summary, value));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.appBar.setTitle(R.string.pref_streaming_enable_title);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pref_streaming, rootKey);
    }

    private void bindOnPreferenceChangeListener(Preference preference) {
        preference.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference.getKey().equals(getString(R.string.pref_key_streaming_hostname))) {
            pref.streamingHostname((String) newValue);
            preference.setSummary((String) newValue);
        } else if (preference.getKey().equals(getString(R.string.pref_key_streaming_port))) {
            if (!TextUtils.isEmpty((String) newValue)) {
                int value = Integer.parseInt((String) newValue);
                pref.streamingPort(value);
                preference.setSummary(Integer.toString(value));
            }
        } else if (preference.getKey().equals(getString(R.string.pref_key_streaming_enable))) {
            pref.enableStreaming((boolean) newValue);
        } else if (preference.getKey().equals(getString(R.string.pref_key_remote_enable))) {
            pref.enableRemoteControl((boolean) newValue);
        } else if (preference.getKey().equals(getString(R.string.pref_key_remote_password))) {
            String value = ((String) newValue).trim();
            if (value.length() < 4)
                return false;
            pref.remoteControlPassword(value);
            preference.setSummary(value);
            ((EditTextPreference) preference).setText(value);
            return false;
        } else if (preference.getKey().equals(getString(R.string.pref_key_remote_port))) {
            if (!TextUtils.isEmpty((String) newValue)) {
                int value = Integer.parseInt((String) newValue);
                pref.remoteControlPort(value);
                preference.setSummary(Integer.toString(value));
                updateRemoteAddress(value);
            }
        }
        return true;
    }
}
