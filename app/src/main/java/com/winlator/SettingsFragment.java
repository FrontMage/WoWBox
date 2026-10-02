package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.box.BoxRuntime;
import com.winlator.box.FrameGenerationManager;
import com.winlator.box.LosslessDllValidator;
import com.winlator.box.WineDebugConfig;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.ArrayUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = WineDebugConfig.DEFAULT_CUSTOM_CHANNELS;
    private static final int IMPORT_LOSSLESS_DLL_REQUEST_CODE = 9301;
    private SharedPreferences preferences;
    private View settingsView;
    private CheckBox cbEnableWineDebug;
    private Spinner sWineDebugProfile;
    private TextView tvEffectiveWineDebug;
    private ArrayList<String> wineDebugChannels;
    private String wineDebugProfile;
    private String lastEnabledWineDebugProfile;
    private boolean syncingWineDebugUi;
    private FrameGenerationManager frameGenerationManager;
    private CheckBox cbFrameGenerationEnabled;
    private CheckBox cbFrameGenerationPerformanceMode;
    private Spinner sFrameGenerationMultiplier;
    private SeekBar sbFrameGenerationFlowScale;
    private TextView tvFrameGenerationFlowScale;
    private TextView tvFrameGenerationStatus;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.settings);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment, container, false);
        final Context context = getContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        final CheckBox cbUseDRI3 = view.findViewById(R.id.CBUseDRI3);
        cbUseDRI3.setChecked(preferences.getBoolean("use_dri3", true));
        final CheckBox cbImeOverlayKeyboard = view.findViewById(R.id.CBImeOverlayKeyboard);
        cbImeOverlayKeyboard.setChecked(preferences.getBoolean("ime_overlay_keyboard", false));
        final CheckBox cbOpenLinksInAndroidBrowser = view.findViewById(R.id.CBOpenLinksInAndroidBrowser);
        cbOpenLinksInAndroidBrowser.setChecked(
                preferences.getBoolean(
                        BoxRuntime.PREF_OPEN_LINKS_IN_ANDROID_BROWSER,
                        BoxRuntime.DEFAULT_OPEN_LINKS_IN_ANDROID_BROWSER));
        final BoxRuntime boxRuntime = BoxRuntime.get(context);
        final CheckBox cbShowPerformanceHud = view.findViewById(R.id.CBShowPerformanceHud);
        cbShowPerformanceHud.setChecked(boxRuntime.isHudEnabled());
        final CheckBox cbTouchMouseGestures = view.findViewById(R.id.CBTouchMouseGestures);
        cbTouchMouseGestures.setChecked(boxRuntime.isTouchGestureEnabled());

        setupWineDebugUi(view);
        setupFrameGenerationUi(view);

        final CheckBox cbEnableAlsaDebug = view.findViewById(R.id.CBEnableAlsaDebug);
        cbEnableAlsaDebug.setChecked(preferences.getBoolean("enable_alsa_debug", false));

        final CheckBox cbEnableDxvkLog = view.findViewById(R.id.CBEnableDxvkLog);
        cbEnableDxvkLog.setChecked(preferences.getBoolean("enable_dxvk_log", false));

        final CheckBox cbEnableWrapperLog = view.findViewById(R.id.CBEnableWrapperLog);
        cbEnableWrapperLog.setChecked(preferences.getBoolean("enable_wrapper_log", false));

        final Spinner sWrapperLogLevel = view.findViewById(R.id.SWrapperLogLevel);
        AppUtils.setSpinnerSelectionFromIdentifier(
                sWrapperLogLevel,
                preferences.getString("wrapper_log_level", "debug")
        );
        sWrapperLogLevel.setEnabled(cbEnableWrapperLog.isChecked());
        cbEnableWrapperLog.setOnCheckedChangeListener((buttonView, isChecked) -> sWrapperLogLevel.setEnabled(isChecked));

        final TextView tvCursorSpeed = view.findViewById(R.id.TVCursorSpeed);
        final SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        sbCursorSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvCursorSpeed.setText(progress+"%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbCursorSpeed.setProgress((int)(preferences.getFloat("cursor_speed", 1.0f) * 100));

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            LosslessDllValidator.Validation frameGenerationDll =
                    frameGenerationManager.validateInstalledDll();
            if (cbFrameGenerationEnabled.isChecked() && !frameGenerationDll.valid) {
                Toast.makeText(
                        context,
                        R.string.frame_generation_requires_dll,
                        Toast.LENGTH_LONG).show();
                refreshFrameGenerationStatus();
                return;
            }

            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("use_dri3", cbUseDRI3.isChecked());
            editor.putBoolean("ime_overlay_keyboard", cbImeOverlayKeyboard.isChecked());
            editor.putBoolean(BoxRuntime.PREF_OPEN_LINKS_IN_ANDROID_BROWSER,
                    cbOpenLinksInAndroidBrowser.isChecked());
            boxRuntime.setHudEnabled(cbShowPerformanceHud.isChecked());
            boxRuntime.setTouchGestureEnabled(cbTouchMouseGestures.isChecked());
            editor.putFloat("cursor_speed", sbCursorSpeed.getProgress() / 100.0f);
            editor.putBoolean("enable_alsa_debug", cbEnableAlsaDebug.isChecked());
            editor.putBoolean("enable_dxvk_log", cbEnableDxvkLog.isChecked());
            editor.putBoolean("enable_wrapper_log", cbEnableWrapperLog.isChecked());
            editor.putString("wrapper_log_level", StringUtils.parseIdentifier(sWrapperLogLevel.getSelectedItem()));
            frameGenerationManager.putSettings(
                    editor,
                    cbFrameGenerationEnabled.isChecked(),
                    sFrameGenerationMultiplier.getSelectedItemPosition() + 2,
                    frameGenerationFlowScale(),
                    cbFrameGenerationPerformanceMode.isChecked());

            WineDebugConfig.putPreferences(
                    editor,
                    cbEnableWineDebug.isChecked()
                            ? wineDebugProfile
                            : WineDebugConfig.PROFILE_DISABLED,
                    wineDebugChannels);

            if (editor.commit()) {
                // conf.toml is watched by the Vulkan layer. Persist the desired
                // settings now, but never hot-switch an active Box session.
                if (frameGenerationDll.valid &&
                        !XServerDisplayActivity.hasActiveSession()) {
                    frameGenerationManager.writeConfig();
                }
                NavigationView navigationView = getActivity().findViewById(R.id.NavigationView);
                navigationView.setCheckedItem(R.id.main_menu_launcher);
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                    .replace(R.id.FLFragmentContainer, new BoxLauncherFragment())
                    .commit();
            }
        });

        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_LOSSLESS_DLL_REQUEST_CODE ||
                resultCode != Activity.RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        Executors.newSingleThreadExecutor().execute(() -> {
            LosslessDllValidator.Validation validation = frameGenerationManager.importDll(uri);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                refreshFrameGenerationStatus();
                Toast.makeText(
                        requireContext(),
                        validation.valid
                                ? getString(R.string.frame_generation_import_success)
                                : getString(
                                        R.string.frame_generation_import_failed,
                                        validation.reason),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void setupFrameGenerationUi(View view) {
        frameGenerationManager = new FrameGenerationManager(requireContext());
        cbFrameGenerationEnabled = view.findViewById(R.id.CBFrameGenerationEnabled);
        cbFrameGenerationPerformanceMode =
                view.findViewById(R.id.CBFrameGenerationPerformanceMode);
        sFrameGenerationMultiplier = view.findViewById(R.id.SFrameGenerationMultiplier);
        sbFrameGenerationFlowScale = view.findViewById(R.id.SBFrameGenerationFlowScale);
        tvFrameGenerationFlowScale = view.findViewById(R.id.TVFrameGenerationFlowScale);
        tvFrameGenerationStatus = view.findViewById(R.id.TVFrameGenerationStatus);

        cbFrameGenerationEnabled.setChecked(frameGenerationManager.isEnabled());
        cbFrameGenerationPerformanceMode.setChecked(
                frameGenerationManager.isPerformanceMode());
        sFrameGenerationMultiplier.setSelection(frameGenerationManager.getMultiplier() - 2);
        sbFrameGenerationFlowScale.setProgress(Math.round(
                (frameGenerationManager.getFlowScale() - 0.25f) * 100.0f));
        sbFrameGenerationFlowScale.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress, boolean fromUser) {
                        tvFrameGenerationFlowScale.setText(String.format(
                                Locale.US, "%.2f", frameGenerationFlowScale()));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
        tvFrameGenerationFlowScale.setText(String.format(
                Locale.US, "%.2f", frameGenerationFlowScale()));

        view.findViewById(R.id.BTImportLosslessDll).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, IMPORT_LOSSLESS_DLL_REQUEST_CODE);
        });
        refreshFrameGenerationStatus();
    }

    private float frameGenerationFlowScale() {
        return 0.25f + (sbFrameGenerationFlowScale.getProgress() / 100.0f);
    }

    private void refreshFrameGenerationStatus() {
        if (tvFrameGenerationStatus == null || frameGenerationManager == null) return;
        LosslessDllValidator.Validation validation =
                frameGenerationManager.validateInstalledDll();
        if (validation.valid) {
            String hashPrefix = validation.sha256.length() >= 12
                    ? validation.sha256.substring(0, 12)
                    : validation.sha256;
            tvFrameGenerationStatus.setText(getString(
                    R.string.frame_generation_dll_ready,
                    validation.version.isEmpty() ? "compatible" : validation.version,
                    validation.size,
                    hashPrefix));
        }
        else {
            tvFrameGenerationStatus.setText(getString(
                    R.string.frame_generation_dll_not_ready,
                    validation.reason));
        }
    }

    private void setupWineDebugUi(View view) {
        settingsView = view;
        cbEnableWineDebug = view.findViewById(R.id.CBEnableWineDebug);
        sWineDebugProfile = view.findViewById(R.id.SWineDebugProfile);
        tvEffectiveWineDebug = view.findViewById(R.id.TVEffectiveWineDebug);

        WineDebugConfig.State state = WineDebugConfig.fromPreferences(preferences);
        wineDebugProfile = state.profile;
        lastEnabledWineDebugProfile = state.isEnabled()
                ? state.profile
                : WineDebugConfig.normalizeStoredProfile(preferences.getString(
                        WineDebugConfig.PREF_LAST_ENABLED_PROFILE,
                        WineDebugConfig.PROFILE_CUSTOM));
        if (WineDebugConfig.PROFILE_DISABLED.equals(lastEnabledWineDebugProfile)) {
            lastEnabledWineDebugProfile = WineDebugConfig.PROFILE_CUSTOM;
        }
        wineDebugChannels = new ArrayList<>(state.channels);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                getContext(),
                R.array.wine_debug_profile_entries,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sWineDebugProfile.setAdapter(adapter);

        syncingWineDebugUi = true;
        cbEnableWineDebug.setChecked(state.isEnabled());
        sWineDebugProfile.setSelection(WineDebugConfig.profileToPosition(wineDebugProfile));
        syncingWineDebugUi = false;
        refreshWineDebugUi();

        cbEnableWineDebug.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingWineDebugUi) return;
            if (!isChecked) {
                setWineDebugProfile(WineDebugConfig.PROFILE_DISABLED, false);
            }
            else if (WineDebugConfig.PROFILE_DISABLED.equals(wineDebugProfile)) {
                setWineDebugProfile(
                        lastEnabledWineDebugProfile,
                        WineDebugConfig.PROFILE_CUSTOM.equals(lastEnabledWineDebugProfile));
            }
        });

        sWineDebugProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                if (syncingWineDebugUi) return;
                String selectedProfile = WineDebugConfig.positionToProfile(position);
                if (!selectedProfile.equals(wineDebugProfile)) {
                    setWineDebugProfile(
                            selectedProfile,
                            WineDebugConfig.PROFILE_CUSTOM.equals(selectedProfile));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setWineDebugProfile(String profile, boolean restoreCustomChannels) {
        String previousProfile = wineDebugProfile;
        wineDebugProfile = WineDebugConfig.normalizeStoredProfile(profile);
        if (!WineDebugConfig.PROFILE_DISABLED.equals(wineDebugProfile)) {
            lastEnabledWineDebugProfile = wineDebugProfile;
        }
        if (WineDebugConfig.PROFILE_CUSTOM.equals(wineDebugProfile) && restoreCustomChannels) {
            String fallbackChannels =
                    WineDebugConfig.PROFILE_CUSTOM.equals(previousProfile)
                    ? preferences.getString(
                            WineDebugConfig.PREF_CHANNELS,
                            WineDebugConfig.DEFAULT_CUSTOM_CHANNELS)
                    : WineDebugConfig.DEFAULT_CUSTOM_CHANNELS;
            String custom = preferences.getString(
                    WineDebugConfig.PREF_CUSTOM_CHANNELS,
                    fallbackChannels);
            wineDebugChannels.clear();
            wineDebugChannels.addAll(WineDebugConfig.parseChannels(custom));
        }
        else {
            String presetChannels = WineDebugConfig.channelsForProfile(wineDebugProfile);
            if (!presetChannels.isEmpty()) {
                wineDebugChannels.clear();
                wineDebugChannels.addAll(WineDebugConfig.parseChannels(presetChannels));
            }
        }

        syncingWineDebugUi = true;
        cbEnableWineDebug.setChecked(
                !WineDebugConfig.PROFILE_DISABLED.equals(wineDebugProfile));
        sWineDebugProfile.setSelection(WineDebugConfig.profileToPosition(wineDebugProfile));
        syncingWineDebugUi = false;
        refreshWineDebugUi();
    }

    private void selectCustomWineDebugProfile() {
        wineDebugProfile = WineDebugConfig.PROFILE_CUSTOM;
        lastEnabledWineDebugProfile = wineDebugProfile;
        syncingWineDebugUi = true;
        cbEnableWineDebug.setChecked(true);
        sWineDebugProfile.setSelection(WineDebugConfig.profileToPosition(wineDebugProfile));
        syncingWineDebugUi = false;
        refreshWineDebugUi();
    }

    private void refreshWineDebugUi() {
        loadWineDebugChannels();
        tvEffectiveWineDebug.setText(WineDebugConfig.buildWineDebug(
                wineDebugProfile,
                wineDebugChannels));
    }

    private void loadWineDebugChannels() {
        final Context context = getContext();
        LinearLayout container = settingsView.findViewById(R.id.LLWineDebugChannels);
        container.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(context);
        View itemView = inflater.inflate(R.layout.wine_debug_channel_list_item, container, false);
        itemView.findViewById(R.id.TextView).setVisibility(View.GONE);
        itemView.findViewById(R.id.BTRemove).setVisibility(View.GONE);

        View addButton = itemView.findViewById(R.id.BTAdd);
        addButton.setVisibility(View.VISIBLE);
        addButton.setOnClickListener((v) -> {
            JSONArray jsonArray = null;
            try {
                jsonArray = new JSONArray(FileUtils.readString(context, "wine_debug_channels.json"));
            }
            catch (JSONException e) {}

            final String[] items = ArrayUtils.toStringArray(jsonArray);
            ContentDialog.showMultipleChoiceList(context, R.string.wine_debug_channel, items, (selectedPositions) -> {
                for (int selectedPosition : selectedPositions) {
                    if (!wineDebugChannels.contains(items[selectedPosition])) {
                        wineDebugChannels.add(items[selectedPosition]);
                    }
                }
                selectCustomWineDebugProfile();
            });
        });

        View resetButton = itemView.findViewById(R.id.BTReset);
        resetButton.setVisibility(View.VISIBLE);
        resetButton.setOnClickListener((v) -> {
            wineDebugChannels.clear();
            wineDebugChannels.addAll(
                    WineDebugConfig.parseChannels(WineDebugConfig.DEFAULT_CUSTOM_CHANNELS));
            selectCustomWineDebugProfile();
        });
        container.addView(itemView);

        for (int i = 0; i < wineDebugChannels.size(); i++) {
            itemView = inflater.inflate(R.layout.wine_debug_channel_list_item, container, false);
            TextView textView = itemView.findViewById(R.id.TextView);
            textView.setText(wineDebugChannels.get(i));
            final int index = i;
            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
                wineDebugChannels.remove(index);
                selectCustomWineDebugProfile();
            });
            container.addView(itemView);
        }
    }

}
