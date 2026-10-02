package com.winlator;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.box.BoxDebugServer;
import com.winlator.box.BoxPaths;
import com.winlator.box.BoxRuntime;
import com.winlator.core.FileUtils;

import org.json.JSONException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BoxDebugFragment extends Fragment {
    private TextView serverInfoView;
    private TextView detailsView;
    private BoxRuntime runtime;

    public BoxDebugFragment() {
        super(R.layout.box_debug_fragment);
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        runtime = BoxRuntime.get(requireContext());
        serverInfoView = view.findViewById(R.id.TVServerInfo);
        detailsView = view.findViewById(R.id.TVDetails);
        Button refreshButton = view.findViewById(R.id.BTRefresh);
        Button restartButton = view.findViewById(R.id.BTRestartContainer);
        Button exportButton = view.findViewById(R.id.BTExportSnapshot);

        refreshButton.setOnClickListener((v) -> refresh());
        restartButton.setOnClickListener((v) -> {
            runtime.launchDesktop();
            refresh();
        });
        exportButton.setOnClickListener((v) -> exportSnapshot());

        BoxDebugServer.get(requireContext()).start();
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(R.string.box_debug_title);
        refresh();
    }

    private void refresh() {
        serverInfoView.setText(getString(R.string.box_debug_server_info,
                runtime.getSpec().debugServer.port,
                runtime.getDebugToken()));
        try {
            detailsView.setText(runtime.buildDebugSnapshot().toString(2));
        }
        catch (JSONException e) {
            detailsView.setText(runtime.buildDebugSnapshot().toString());
        }
    }

    private void exportSnapshot() {
        File exportDir = BoxPaths.getStateExportDir(runtime.getSpec());
        exportDir.mkdirs();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(exportDir, "snapshot-" + timestamp + ".json");
        FileUtils.writeString(file, runtime.buildDebugSnapshot().toString());
        refresh();
    }
}
