package com.winlator;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.box.BoxPaths;
import com.winlator.box.BoxDebugConfig;
import com.winlator.box.BoxInstaller;
import com.winlator.box.BoxLaunchController;
import com.winlator.box.BoxLaunchRequest;
import com.winlator.box.BoxRuntime;
import com.winlator.box.BoxSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BoxLauncherFragment extends Fragment {
    private static final String BATTLENET_DOWNLOAD_URL = "https://download.battle.net/en-us/desktop";
    private static final String BATTLENET_TARGET_ID = "bnet";
    private static final String BATTLENET_INSTALLER_NAME = "Battle.net-Setup.exe";

    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView summaryView;
    private View actionsView;
    private View launcherSectionView;
    private Button desktopButton;
    private Button downloadBattleNetButton;
    private Button runtimeUpdateButton;
    private TextView runtimeUpdateStatus;
    private boolean upgrading;
    private boolean recovering;
    private boolean recoveryFailed;
    private boolean gpuFallback;
    private boolean gpuBlocked;
    private final BoxLaunchController launchController = new BoxLaunchController();
    private BoxRuntime runtime;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.box_launcher_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        runtime = BoxRuntime.get(requireContext());
        recyclerView = view.findViewById(R.id.RecyclerView);
        emptyView = view.findViewById(R.id.TVEmptyText);
        summaryView = view.findViewById(R.id.TVSummary);
        actionsView = view.findViewById(R.id.LLActions);
        launcherSectionView = view.findViewById(R.id.TVLauncherSection);
        desktopButton = view.findViewById(R.id.BTDesktop);
        downloadBattleNetButton = view.findViewById(R.id.BTDownloadBattleNet);
        runtimeUpdateButton = view.findViewById(R.id.BTRuntimeUpdate);
        runtimeUpdateStatus = view.findViewById(R.id.TVRuntimeUpdateStatus);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        desktopButton.setOnClickListener((v) -> launchOrShowError(null, null));
        downloadBattleNetButton.setOnClickListener((v) -> openBattleNetDownloadPage());
        runtimeUpdateButton.setOnClickListener((v) -> {
            if (recoveryFailed) refreshRuntimeState();
            else updateRuntime();
        });

        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.box_launcher_title);
        if (launchController.isPending()) showLaunchChecking();
        else refreshRuntimeState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (runtime != null) {
            refreshRuntimeState();
        }
    }

    @Override
    public void onPause() {
        launchController.cancelPending();
        launchController.releaseCommitted();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        launchController.cancelPending();
        launchController.releaseCommitted();
        super.onDestroyView();
    }

    private void refreshRuntimeState() {
        if (recovering || upgrading || launchController.isPending()) return;
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) return;
        recovering = true;
        runtimeUpdateStatus.setVisibility(View.VISIBLE);
        runtimeUpdateStatus.setText(R.string.box_update_checking);
        runtimeUpdateButton.setEnabled(false);
        desktopButton.setEnabled(false);
        new Thread(() -> {
            String error = null;
            boolean fallback = false;
            boolean blocked = false;
            try {
                runtime.recoverPendingReleaseBeforeLaunch();
                org.json.JSONObject gpu = runtime.legacyRedMagicGpuOverrideStatus();
                fallback = gpu.optBoolean("fallbackToOriginal", false);
                blocked = !gpu.optBoolean("ready", false);
            }
            catch (Exception failure) {
                error = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
            }
            final String result = error;
            final boolean gpuFallbackResult = fallback;
            final boolean gpuBlockedResult = blocked;
            activity.runOnUiThread(() -> {
                recovering = false;
                if (!isAdded() || getView() == null) return;
                if (result != null) {
                    recoveryFailed = true;
                    android.util.Log.e("WoWBox", "Runtime recovery failed: " + result);
                    runtimeUpdateStatus.setText(R.string.box_update_recovery_failed);
                    runtimeUpdateButton.setVisibility(View.VISIBLE);
                    runtimeUpdateButton.setText(R.string.box_update_retry_recovery);
                    runtimeUpdateButton.setEnabled(true);
                    return;
                }
                recoveryFailed = false;
                gpuFallback = gpuFallbackResult;
                gpuBlocked = gpuBlockedResult;
                runtimeUpdateButton.setText(R.string.box_update_action);
                loadExecutables();
            });
        }, "wowbox-runtime-recovery").start();
    }

    private void loadExecutables() {
        boolean updateRequired = new BoxInstaller(requireContext()).needsBundledReleaseUpgrade();
        runtimeUpdateButton.setVisibility(updateRequired ? View.VISIBLE : View.GONE);
        runtimeUpdateStatus.setVisibility(updateRequired || gpuFallback || gpuBlocked ? View.VISIBLE : View.GONE);
        if (updateRequired && !upgrading) runtimeUpdateStatus.setText(R.string.box_update_required);
        else if (com.winlator.box.GpuFallbackNoticePolicy.showBlocked(gpuBlocked, updateRequired, upgrading)) runtimeUpdateStatus.setText(R.string.box_gpu_verify_failed);
        else if (com.winlator.box.GpuFallbackNoticePolicy.showFallback(gpuFallback, updateRequired, upgrading)) runtimeUpdateStatus.setText(R.string.box_gpu_original_fallback);
        runtimeUpdateButton.setEnabled(!upgrading);
        desktopButton.setEnabled(!updateRequired && !upgrading && !gpuBlocked);
        downloadBattleNetButton.setEnabled(!launchController.isPending());
        boolean battleNetInstalled = runtime.launchTargetExistsById(BATTLENET_TARGET_ID);
        File installer = new File(BoxPaths.getPayloadDir(runtime.getSpec()), BATTLENET_INSTALLER_NAME);
        boolean installerReady = installer.isFile();
        downloadBattleNetButton.setVisibility(battleNetInstalled ? View.GONE : View.VISIBLE);

        ArrayList<LaunchItem> items = new ArrayList<>();
        for (BoxSpec.Target target : runtime.getVisibleLaunchTargets()) {
            String title = target.label == null || target.label.isEmpty() ? target.id : target.label;
            items.add(new LaunchItem(title, target.guestPath, target.id, null));
        }
        recyclerView.setAdapter(new LaunchItemAdapter(items));
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        launcherSectionView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (!battleNetInstalled && !installerReady) {
            summaryView.setVisibility(View.VISIBLE);
            summaryView.setText(getString(R.string.box_bnet_installer_missing, installer.getAbsolutePath()));
        } else {
            summaryView.setVisibility(View.GONE);
        }
        desktopButton.setVisibility(runtime.getSpec().launch.desktopButtonEnabled ? View.VISIBLE : View.GONE);
        boolean hasVisibleAction = desktopButton.getVisibility() == View.VISIBLE ||
                downloadBattleNetButton.getVisibility() == View.VISIBLE;
        actionsView.setVisibility(hasVisibleAction ? View.VISIBLE : View.GONE);
    }

    private void openBattleNetDownloadPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BATTLENET_DOWNLOAD_URL)));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.box_bnet_download_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void launchOrShowError(String targetId, String hostPath) {
        if (launchController.isPending()) return;
        if (recovering || recoveryFailed || upgrading) {
            Toast.makeText(requireContext(), R.string.box_update_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (gpuBlocked) {
            Toast.makeText(requireContext(), R.string.box_gpu_verify_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null || !isResumed()) return;
        final android.content.Context appContext = activity.getApplicationContext();
        final BoxLaunchRequest request = launchController.begin();
        if (request == null) return;
        showLaunchChecking();
        new Thread(() -> {
            RuntimeException error = null;
            try {
                if (new BoxInstaller(appContext).needsBundledReleaseUpgrade()) {
                    throw new IllegalStateException("release_upgrade_required");
                }
                if (targetId != null) runtime.launchTarget(targetId, null, request);
                else if (hostPath != null) runtime.launchExecutable(hostPath, BoxDebugConfig.none(), request);
                else runtime.launchDesktop(BoxDebugConfig.none(), request);
            } catch (RuntimeException failure) {
                error = failure;
            }
            final RuntimeException failure = error;
            activity.runOnUiThread(() -> {
                if (failure == null) {
                    if (!isAdded() || getView() == null || !isResumed()) {
                        launchController.finish(request);
                        return;
                    }
                    // Keep taps disabled until the target Activity actually
                    // pauses this Fragment. Recover if Android rejects the
                    // background Activity request without throwing.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!launchController.finish(request)) return;
                        if (!isAdded() || getView() == null || !isResumed()) return;
                        Toast.makeText(activity, R.string.box_launch_failed, Toast.LENGTH_LONG).show();
                        refreshRuntimeState();
                    }, 5000);
                    return;
                }
                if (!launchController.finish(request)) return;
                if (!isAdded() || getView() == null || !isResumed()) return;
                downloadBattleNetButton.setEnabled(true);
                if (!(failure instanceof java.util.concurrent.CancellationException)) {
                    int message = "wow_input_bridge_migration_required".equals(failure.getMessage())
                            ? R.string.box_input_bridge_migration_required
                            : "release_upgrade_required".equals(failure.getMessage())
                                    ? R.string.box_update_required : R.string.box_launch_failed;
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
                refreshRuntimeState();
            });
        }, "wowbox-verified-launch").start();
    }

    private void showLaunchChecking() {
        runtimeUpdateStatus.setVisibility(View.VISIBLE);
        runtimeUpdateStatus.setText(R.string.box_launch_preparing);
        runtimeUpdateButton.setEnabled(false);
        desktopButton.setEnabled(false);
        downloadBattleNetButton.setEnabled(false);
        if (recyclerView.getAdapter() != null) recyclerView.getAdapter().notifyDataSetChanged();
    }

    private void updateRuntime() {
        if (upgrading) return;
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) return;
        upgrading = true;
        runtimeUpdateButton.setEnabled(false);
        desktopButton.setEnabled(false);
        runtimeUpdateStatus.setText(R.string.box_update_running);
        final BoxInstaller installer = new BoxInstaller(activity.getApplicationContext());
        new Thread(() -> {
            String error = null;
            try {
                installer.upgradeBundledRelease(null);
            }
            catch (RuntimeException failure) {
                error = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
            }
            final String result = error;
            activity.runOnUiThread(() -> {
                upgrading = false;
                if (!isAdded() || getView() == null) return;
                loadExecutables();
                if (result == null) {
                    Toast.makeText(activity, R.string.box_update_complete, Toast.LENGTH_LONG).show();
                }
                else {
                    android.util.Log.e("WoWBox", "Runtime update failed: " + result);
                    runtimeUpdateStatus.setVisibility(View.VISIBLE);
                    runtimeUpdateStatus.setText(updateErrorMessage(result));
                }
            });
        }, "wowbox-runtime-update").start();
    }

    private int updateErrorMessage(String error) {
        String value = error.toLowerCase(java.util.Locale.US);
        if (value.contains("insufficient") || value.contains("free space")) return R.string.box_update_not_enough_space;
        if (value.contains("active_wine") || value.contains("requires_idle")) return R.string.box_update_close_game;
        if (value.contains("unknown") || value.contains("unsupported") || value.contains("protected") ||
                value.contains("overlay") || value.contains("unverified")) return R.string.box_update_existing_data_not_supported;
        return R.string.box_update_failed;
    }

    private static class LaunchItem {
        final String title;
        final String subtitle;
        final String targetId;
        final String hostPath;

        LaunchItem(String title, String subtitle, String targetId, String hostPath) {
            this.title = title;
            this.subtitle = subtitle;
            this.targetId = targetId;
            this.hostPath = hostPath;
        }
    }

    private class LaunchItemAdapter extends RecyclerView.Adapter<LaunchItemAdapter.ViewHolder> {
        private final List<LaunchItem> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView title;
            private final TextView subtitle;

            private ViewHolder(View view) {
                super(view);
                title = view.findViewById(R.id.TVTitle);
                subtitle = view.findViewById(R.id.TVSubtitle);
            }
        }

        private LaunchItemAdapter(List<LaunchItem> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.box_executable_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LaunchItem item = data.get(position);
            holder.title.setText(item.title);
            holder.subtitle.setText(item.subtitle);
            holder.itemView.setEnabled(!launchController.isPending());
            holder.itemView.setOnClickListener((v) -> launchOrShowError(item.targetId, item.hostPath));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
