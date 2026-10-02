package com.winlator;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.box.TitanProfileHostKeyStore;
import com.winlator.box.TitanProfileSyncConfig;
import com.winlator.box.TitanProfileSyncManager;
import com.winlator.contentdialog.ContentDialog;

public final class TitanProfileSyncFragment extends Fragment
        implements TitanProfileSyncManager.Listener {
    private EditText hostView;
    private EditText portView;
    private EditText usernameView;
    private EditText passwordView;
    private EditText sourcePathView;
    private Button testButton;
    private Button syncButton;
    private Button cancelButton;
    private Button forgetHostButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private TextView trustView;
    private TextView lastSyncView;
    private TitanProfileSyncManager manager;
    private ContentDialog trustDialog;

    public TitanProfileSyncFragment() {
        super(R.layout.titan_profile_sync_fragment);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        hostView = view.findViewById(R.id.ETTitanSyncHost);
        portView = view.findViewById(R.id.ETTitanSyncPort);
        usernameView = view.findViewById(R.id.ETTitanSyncUsername);
        passwordView = view.findViewById(R.id.ETTitanSyncPassword);
        sourcePathView = view.findViewById(R.id.ETTitanSyncSourcePath);
        testButton = view.findViewById(R.id.BTTitanSyncTest);
        syncButton = view.findViewById(R.id.BTTitanSyncStart);
        cancelButton = view.findViewById(R.id.BTTitanSyncCancel);
        forgetHostButton = view.findViewById(R.id.BTTitanSyncForgetHost);
        progressBar = view.findViewById(R.id.PBTitanSync);
        statusView = view.findViewById(R.id.TVTitanSyncStatus);
        trustView = view.findViewById(R.id.TVTitanSyncTrust);
        lastSyncView = view.findViewById(R.id.TVTitanSyncLast);

        passwordView.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TitanProfileSyncConfig config = TitanProfileSyncConfig.load(requireContext());
        hostView.setText(config.host);
        portView.setText(Integer.toString(config.port));
        usernameView.setText(config.username);
        sourcePathView.setText(config.sourcePath);

        manager = new TitanProfileSyncManager(requireContext());
        manager.setListener(this);
        updateSavedState(config);

        testButton.setOnClickListener(v ->
                start(TitanProfileSyncManager.Action.TEST));
        syncButton.setOnClickListener(v ->
                start(TitanProfileSyncManager.Action.SYNC));
        cancelButton.setOnClickListener(v -> manager.cancel());
        forgetHostButton.setOnClickListener(v -> {
            manager.forgetTrustedHost();
            updateSavedState(readConfig());
        });

        ((AppCompatActivity) requireActivity()).getSupportActionBar()
                .setTitle(R.string.titan_profile_sync_title);
    }

    @Override
    public void onStop() {
        if (manager != null) manager.cancel();
        clearKeepScreenOn();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (trustDialog != null) {
            trustDialog.dismiss();
            trustDialog = null;
        }
        if (manager != null) {
            manager.setListener(null);
            manager.close();
            manager = null;
        }
        clearKeepScreenOn();
        super.onDestroyView();
    }

    @Override
    public void onStateChanged(TitanProfileSyncManager.State state) {
        if (!isAdded() || getView() == null) return;
        boolean busy = state.active || state.awaitingTrust;
        setInputsEnabled(!busy);
        cancelButton.setVisibility(state.active ? View.VISIBLE : View.GONE);
        progressBar.setProgress(state.progress);
        progressBar.setVisibility(state.active || state.success ? View.VISIBLE : View.GONE);
        statusView.setText(state.message);
        if (state.active) {
            requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        else {
            clearKeepScreenOn();
        }
        updateSavedState(readConfig());
    }

    @Override
    public void onHostKeyTrustRequired(TitanProfileHostKeyStore.HostKeyInfo hostKey) {
        if (!isAdded() || getView() == null) {
            manager.rejectTrust();
            return;
        }
        if (trustDialog != null) trustDialog.dismiss();
        trustDialog = new ContentDialog(requireContext());
        trustDialog.setTitle(R.string.titan_profile_sync_trust_title);
        trustDialog.setMessage(getString(
                R.string.titan_profile_sync_trust_message,
                hostKey.hostIdentity,
                hostKey.fingerprint));
        trustDialog.setOnConfirmCallback(() -> {
            trustDialog = null;
            manager.trustAndContinue();
        });
        trustDialog.setOnCancelCallback(() -> {
            trustDialog = null;
            manager.rejectTrust();
        });
        trustDialog.setOnDismissListener(dialog -> trustDialog = null);
        trustDialog.show();
    }

    private void start(TitanProfileSyncManager.Action action) {
        TitanProfileSyncConfig config = readConfig();
        String password = passwordView.getText().toString();
        manager.start(action, config, password);
        if (!password.isEmpty()) {
            passwordView.setText("");
            passwordView.setHint(R.string.titan_profile_sync_saved_password);
        }
    }

    private TitanProfileSyncConfig readConfig() {
        int port = TitanProfileSyncConfig.DEFAULT_PORT;
        try {
            port = Integer.parseInt(portView.getText().toString().trim());
        }
        catch (NumberFormatException ignored) {}
        return new TitanProfileSyncConfig(
                hostView.getText().toString(),
                port,
                usernameView.getText().toString(),
                sourcePathView.getText().toString());
    }

    private void setInputsEnabled(boolean enabled) {
        hostView.setEnabled(enabled);
        portView.setEnabled(enabled);
        usernameView.setEnabled(enabled);
        passwordView.setEnabled(enabled);
        sourcePathView.setEnabled(enabled);
        testButton.setEnabled(enabled);
        syncButton.setEnabled(enabled);
        forgetHostButton.setEnabled(enabled);
    }

    private void updateSavedState(TitanProfileSyncConfig config) {
        if (manager == null) return;
        passwordView.setHint(manager.hasSavedPassword()
                ? R.string.titan_profile_sync_saved_password
                : R.string.titan_profile_sync_password_hint);
        String fingerprint = manager.getTrustedFingerprint(config);
        trustView.setText(fingerprint.isEmpty()
                ? getString(R.string.titan_profile_sync_host_untrusted)
                : getString(R.string.titan_profile_sync_host_trusted, fingerprint));
        lastSyncView.setText(manager.getLastSummary());
    }

    private void clearKeepScreenOn() {
        if (getActivity() != null) {
            getActivity().getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
