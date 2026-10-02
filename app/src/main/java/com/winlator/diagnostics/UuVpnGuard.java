package com.winlator.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.winlator.BuildConfig;
import com.winlator.R;

/**
 * Warns the local UU package-alias experiment when it starts or continues on a direct network.
 * The production package never enables this behavior.
 */
public final class UuVpnGuard implements AutoCloseable {
    private static final String TAG = "UuVpnGuard";
    private static final String REQUIRED_PACKAGE = "com.blizzard.diablo.immortal";
    private static final long LOSS_CONFIRMATION_DELAY_MS = 1500L;

    private final Activity activity;
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable confirmLoss;

    private boolean closed;
    private boolean warningShown;
    private boolean hasObservedReady;
    private Toast warningToast;

    private UuVpnGuard(Activity activity) {
        this.activity = activity;
        connectivityManager =
                (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        confirmLoss = () -> {
            if (closed || isReady(this.activity)) return;
            showLossWarning();
        };
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleStateCheck();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                scheduleStateCheck();
            }

            @Override
            public void onLost(Network network) {
                scheduleStateCheck();
            }
        };
    }

    public static boolean isRequired(Context context) {
        return BuildConfig.UU_PROBE_BUILD &&
                REQUIRED_PACKAGE.equals(context.getApplicationContext().getPackageName());
    }

    public static boolean isReady(Context context) {
        if (!isRequired(context)) return true;

        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;

        Network network = manager.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public static UuVpnGuard start(Activity activity) {
        if (!isRequired(activity)) return null;

        UuVpnGuard guard = new UuVpnGuard(activity);
        if (guard.connectivityManager == null) {
            guard.showLossWarning();
            return guard;
        }

        try {
            guard.connectivityManager.registerDefaultNetworkCallback(guard.networkCallback);
        }
        catch (RuntimeException e) {
            Log.w(TAG, "Unable to register the UU VPN network callback", e);
        }
        guard.scheduleStateCheck();
        return guard;
    }

    public void onHostResumed() {
        scheduleStateCheck();
    }

    private void scheduleStateCheck() {
        mainHandler.post(this::evaluateState);
    }

    private void evaluateState() {
        if (closed) return;
        mainHandler.removeCallbacks(confirmLoss);
        if (isReady(activity)) {
            hasObservedReady = true;
            warningShown = false;
            if (warningToast != null) {
                warningToast.cancel();
                warningToast = null;
            }
            return;
        }
        mainHandler.postDelayed(confirmLoss, LOSS_CONFIRMATION_DELAY_MS);
    }

    private void showLossWarning() {
        if (closed || warningShown || activity.isFinishing() || activity.isDestroyed()) return;

        warningShown = true;
        Log.w(TAG, "UU VPN is unavailable for the local package-alias session");
        int message = hasObservedReady
                ? R.string.uu_vpn_lost_message
                : R.string.uu_vpn_unavailable_message;
        warningToast = Toast.makeText(activity, message, Toast.LENGTH_LONG);
        warningToast.show();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            catch (RuntimeException ignored) {}
        }
        if (warningToast != null) {
            warningToast.cancel();
            warningToast = null;
        }
    }
}
