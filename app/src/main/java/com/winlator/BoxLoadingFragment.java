package com.winlator;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.box.BoxDebugServer;
import com.winlator.box.BoxInstallListener;
import com.winlator.box.BoxInstaller;
import com.winlator.box.BoxRuntime;

public class BoxLoadingFragment extends Fragment implements BoxInstallListener {
    private ProgressBar overallProgressBar;
    private ProgressBar currentProgressBar;
    private TextView stageView;
    private TextView detailView;
    private TextView statusView;
    private Button retryButton;
    private Button dryRunButton;
    private volatile boolean started;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.box_loading_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        overallProgressBar = view.findViewById(R.id.OverallProgressBar);
        currentProgressBar = view.findViewById(R.id.CurrentProgressBar);
        stageView = view.findViewById(R.id.TVStage);
        detailView = view.findViewById(R.id.TVDetail);
        statusView = view.findViewById(R.id.TVStatus);
        retryButton = view.findViewById(R.id.BTRetry);
        dryRunButton = view.findViewById(R.id.BTDryRun);

        retryButton.setOnClickListener((v) -> startInstall(false));
        dryRunButton.setOnClickListener((v) -> startInstall(true));

        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.box_loading_title);
        if (BoxRuntime.get(requireContext()).isInstalled()) {
            ((MainActivity) requireActivity()).showLauncher();
            return;
        }
        startInstall(false);
    }

    private void startInstall(boolean dryRun) {
        if (started) return;
        started = true;
        retryButton.setEnabled(false);
        dryRunButton.setEnabled(false);
        statusView.setText(dryRun ? R.string.box_dry_run_running : R.string.box_install_running);
        new BoxInstaller(requireContext()).installAsync(dryRun, (success) -> requireActivity().runOnUiThread(() -> {
            started = false;
            retryButton.setEnabled(true);
            dryRunButton.setEnabled(true);
            if (success) {
                statusView.setText(dryRun ? R.string.box_dry_run_complete : R.string.box_install_complete);
                if (!dryRun) {
                    BoxDebugServer.get(requireContext()).start();
                    ((MainActivity) requireActivity()).showLauncher();
                }
            } else {
                statusView.setText(R.string.box_install_failed);
            }
        }), this);
    }

    @Override
    public void onProgress(String stage, int overallProgress, int currentProgress, String detail) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            overallProgressBar.setProgress(overallProgress);
            currentProgressBar.setProgress(currentProgress);
            stageView.setText(stage);
            detailView.setText(detail != null ? detail : "");
        });
    }
}
