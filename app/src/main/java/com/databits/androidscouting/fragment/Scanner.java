package com.databits.androidscouting.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;  // For alert dialogs
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.mlkit.vision.MlKitAnalyzer;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.databits.androidscouting.R;
import com.databits.androidscouting.databinding.FragmentScannerBinding;
import com.databits.androidscouting.model.QrCodeDrawable;
import com.databits.androidscouting.model.QrCodeViewModel;
import com.databits.androidscouting.util.MatchInfo;
import com.databits.androidscouting.util.PreferenceManager;
import com.databits.androidscouting.util.QrCsvParser; // Helper class for CSV parsing
import com.databits.androidscouting.util.ScoutUtils;
import com.databits.androidscouting.util.SheetsUpdateTask;
import com.databits.androidscouting.util.TeamInfo;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.opencsv.CSVWriter;
import com.preference.PowerPreference;
import com.preference.Preference;
import com.travijuu.numberpicker.library.NumberPicker;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED;

public class Scanner extends Fragment {

    private static final String TAG = "ScannerFragment"; // Tag for logging

    protected BarcodeScanner qrScanner;
    protected ExecutorService cameraExecutor;
    private FragmentScannerBinding binding;

    // Retrieve preferences via PreferenceManager for consistency.
    Preference configPreference = PreferenceManager.getInstance().getConfigPreference();
    Preference listPreference = PreferenceManager.getInstance().getListPreference();
    Preference debugPreference = PreferenceManager.getInstance().getDebugPreference();
    Preference matchPreference = PreferenceManager.getInstance().getMatchPreference();
    Preference defaultPreference = PowerPreference.getDefaultFile();

    MatchInfo matchInfo;
    TeamInfo teamInfo;
    ScoutUtils scoutUtils;

    int match;

    PreviewView preview;
    LifecycleCameraController camController;

    // These variables hold the last scanned QR data and error message.
    private String lastScannedData = null;
    private String lastScanError = null;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Register the menu provider for this fragment with debug options.
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.scanner_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_change_view) {
                    // Toggle the 'isMaster' flag.
                    debugPreference.setBoolean("isMaster", !debugPreference.getBoolean("isMaster", false));
                    refreshUI();
                }
                if (id == R.id.action_debug) {
                    // Launch debug screen.
                    PowerPreference.showDebugScreen(true);
                }
                return false;
            }
        }, this.getViewLifecycleOwner(), Lifecycle.State.CREATED);

        binding = FragmentScannerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Set full-screen immersive mode.
        View decorView = requireActivity().getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);

        // Initialize utility classes.
        matchInfo = new MatchInfo();
        teamInfo = new TeamInfo(getContext());
        scoutUtils = new ScoutUtils(getContext());
        match = matchInfo.getMatch();

        String role = configPreference.getString("device_role");

        // Set up navigation.
        NavController controller = NavHostFragment.findNavController(Scanner.this);
        binding.buttonBack.setOnClickListener(view1 -> controller.navigateUp());

        // Upload button: if no valid scan is available, show alert.
        binding.buttonUpload.setOnClickListener(view1 -> {
            if (lastScanError != null) {
                showScanRetryDialog(lastScanError);
            } else if (lastScannedData == null || lastScannedData.trim().isEmpty()) {
                showScanRetryDialog("No QR code data scanned. Please scan a valid QR code before uploading.");
            } else {
                // Save the scanned data only when the user uploads.
                saveData(lastScannedData);
                call_sheets();
                // Clear the temporary data after uploading to prevent duplicates.
                lastScannedData = null;
            }
        });


        // Set up upload mode selection.
        binding.buttonGroupUploadMode.setOnPositionChangedListener(position -> {
            switch (position) {
                case 0:
                    configPreference.setString("uploadMode", "Crowd");
                    break;
                case 1:
                    configPreference.setString("uploadMode", "Specialty");
                    break;
                case 2:
                    configPreference.setString("uploadMode", "Pit");
                    break;
            }
        });

        binding.testButton.setOnClickListener(view1 -> PowerPreference.showDebugScreen(true));

        // Configure the match number picker.
        NumberPicker matchCounter = binding.uiInsideNumberPicker;
        matchCounter = matchInfo.configurePicker(matchCounter);

        // Load teams if not already loaded.
        if (listPreference.getObject("team_match", String[][].class) == null) {
            teamInfo.read_teams();
        }
        if (teamInfo.teamsLoaded()) {
            setupTeamDisplay(match);
        }

        // Listen for changes to the match counter.
        matchCounter.setValueChangedListener((value, action) -> {
            matchInfo.setMatch(value);
            setupTeamDisplay(value);
        });

        // Adjust UI based on role preferences.
        if (configPreference.getBoolean("role_locked_toggle") && (!role.equals("master"))) {
            debugPreference.setBoolean("isMaster", false);
        } else if (role.equals("master")) {
            debugPreference.setBoolean("isMaster", true);
            binding.buttonBack.setVisibility(View.INVISIBLE);
        }

        // Initialize the camera and start scanning.
        cameraExecutor = Executors.newSingleThreadExecutor();
        preview = binding.previewView;
        refreshUI();
        openCamera();
    }

    /**
     * Initializes the camera and sets up QR scanning.
     */
    protected void openCamera() {
        // Configure ML Kit to detect only QR codes.
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build();
        qrScanner = BarcodeScanning.getClient(options);
        camController = new LifecycleCameraController(requireContext());
        camController.setCameraSelector(new CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build());
        // Set the analyzer and integrate QrCsvParser for consistent QR parsing.
        camController.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(requireContext()),
            getQrCodeAnalyzer(preview));
        camController.setTapToFocusEnabled(true);
        camController.setPinchToZoomEnabled(true);
        camController.bindToLifecycle(this);
        preview.setController(camController);
        refreshActionBar();
    }

    /**
     * Returns an ML Kit analyzer for QR codes.
     * Uses QrCsvParser to parse CSV data consistently.
     */
    protected MlKitAnalyzer getQrCodeAnalyzer(PreviewView preview) {
        return new MlKitAnalyzer(
            Collections.singletonList(qrScanner),
            COORDINATE_SYSTEM_VIEW_REFERENCED,
            ContextCompat.getMainExecutor(requireContext()),
            result -> {
                try {
                    // Get detected barcodes.
                    List<Barcode> qrResList = result.getValue(qrScanner);
                    if (qrResList == null || qrResList.isEmpty() || qrResList.get(0) == null) {
                        preview.getOverlay().clear();
                        return;
                    }
                    Barcode qr = qrResList.get(0);
                    String rawData = qr.getRawValue();
                    preview.getOverlay().clear();

                    // Draw an overlay for visual feedback.
                    QrCodeViewModel qrCodeViewModel = new QrCodeViewModel(qr);
                    QrCodeDrawable qrCodeDrawable = new QrCodeDrawable(qrCodeViewModel);
                    preview.getOverlay().add(qrCodeDrawable);

                    if (rawData == null) {
                        Log.w(TAG, "QR code raw value is null");
                        return;
                    }

                    // Use QrCsvParser to parse the raw CSV data.
                    String[] parsedFields = QrCsvParser.parseCsv(rawData, 1);
                    if (parsedFields == null || parsedFields.length == 0) {
                        lastScannedData = null;
                        lastScanError = "Invalid or malformed QR data.";
                        return;
                    }
                    Log.d(TAG, "Parsed QR fields: " + Arrays.toString(parsedFields));

                    // Process QR data based on known prefixes.
                    if (rawData.startsWith("ScoutData")) {
                        debugPreference.setObject("scouter_list", parsedFields);
                    } else if (rawData.startsWith("GoogleConfig")) {
                        String[] parts = rawData.split(",");
                        if (parts.length >= 5) {
                            configPreference.setString("workbook_id", parts[1].trim());
                            configPreference.setString("crowd_range", parts[2].trim());
                            configPreference.setString("pit_range", parts[3].trim());
                            configPreference.setString("specialty_range", parts[4].trim());
                        } else {
                            runOnUiThread(() -> showScanRetryDialog("Incomplete GoogleConfig data. Please scan again."));
                            return;
                        }
                    } else if (rawData.startsWith("MatchData")) {
                        List<String[]> matchData = splitMatchData(rawData);
                        if (matchData.isEmpty()) {
                            lastScannedData = null;
                            lastScanError = "Incomplete MatchData. Please scan again.";
                            return;
                        }
                        String[][] originalMatchData = listPreference.getObject("team_match", String[][].class, new String[0][0]);
                        String[][] combinedMatchData = new String[originalMatchData.length + matchData.size()][];
                        System.arraycopy(originalMatchData, 0, combinedMatchData, 0, originalMatchData.length);
                        System.arraycopy(matchData.toArray(new String[0][0]), 0, combinedMatchData,
                            originalMatchData.length, matchData.size());
                        // Normalize and sort match numbers.
                        for (String[] entry : combinedMatchData) {
                            try {
                                entry[0] = String.valueOf(Integer.parseInt(entry[0]));
                            } catch (NumberFormatException e) {
                                entry[0] = "0";
                                Log.e(TAG, "Non-numeric match number encountered, defaulting to 0", e);
                            }
                        }
                        Arrays.sort(combinedMatchData, Comparator.comparingInt(a -> Integer.parseInt(a[0])));
                        int newLength = combinedMatchData.length;
                        // Deduplicate identical lines.
                        for (int i = 1; i < newLength; i++) {
                            if (Arrays.equals(combinedMatchData[i], combinedMatchData[i - 1])) {
                                System.arraycopy(combinedMatchData, i + 1, combinedMatchData, i, newLength - i - 1);
                                newLength--;
                                i--;
                            }
                        }
                        String[][] uniqueMatchData = new String[newLength][];
                        System.arraycopy(combinedMatchData, 0, uniqueMatchData, 0, newLength);
                        debugPreference.setInt("team_match_list_size", newLength);
                        listPreference.setObject("team_match", uniqueMatchData);
                        setupTeamDisplay(match);
                    } else if (rawData.startsWith("role")) {
                        process_qr(rawData);
                    }  else {
                    // For generic QR data, just store it temporarily.
                    lastScannedData = rawData;
                }
                lastScanError = null;

            } catch (Exception e) {
                    Log.e(TAG, "Error processing QR code", e);
                    lastScannedData = null;
                    lastScanError = "An error occurred while processing the QR code. Please scan again.";
                }
            }
        );
    }

    /**
     * Helper method to run actions on the UI thread.
     */
    private void runOnUiThread(Runnable action) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(action);
        }
    }

    /**
     * Displays an alert dialog when a scan error occurs.
     */
    private void showScanRetryDialog(String message) {
        if (getActivity() == null) return;
        new AlertDialog.Builder(getActivity())
            .setTitle("Scan Error")
            .setMessage(message)
            .setPositiveButton("Retry", (dialog, which) -> dialog.dismiss())
            .setCancelable(false)
            .show();
    }

    /**
     * Parses the match data string into a list of string arrays.
     * Splits the data into chunks and processes each entry.
     */
    public List<String[]> splitMatchData(String matchDataString) {
        List<String[]> result = new ArrayList<>();
        if (!matchDataString.startsWith("MatchData")) {
            Log.e(TAG, "Invalid data format: Does not start with 'MatchData'");
            return result;
        }
        String[] parts = matchDataString.split(",", 3);
        if (parts.length != 3) {
            Log.e(TAG, "Invalid data format: Incorrect number of commas");
            return result;
        }
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid data format: Chunk index not a number", e);
            return result;
        }
        // Prevent processing duplicate chunks.
        Set<String> processedChunks = listPreference.getObject("processedChunks", Set.class, new HashSet<>());
        if (processedChunks.contains(chunkIndex + "")) {
            Log.i(TAG, "Duplicate chunk detected: " + chunkIndex + ". Skipping.");
            return result;
        }
        processedChunks.add(chunkIndex + "");
        listPreference.setObject("processedChunks", processedChunks);

        // Split the match entries.
        String[] matchEntries = parts[2].split("(?<=])(?=\\[)");
        for (String entry : matchEntries) {
            String cleanedEntry = entry.replaceAll("[\\[\\]]", "");
            String[] values = cleanedEntry.split(",");
            for (int i = 0; i < values.length; i++) {
                values[i] = values[i].trim();
            }
            result.add(values);
        }
        return result;
    }

    /**
     * Creates or appends to the upload CSV file.
     * This is used for debugging purposes.
     */
    private void makeUploadFile(String bar_string) {
        File file = new File(requireContext().getFilesDir(), "upload.csv");
        try (FileWriter uploadFile = new FileWriter(file, true);
             CSVWriter uploader = new CSVWriter(uploadFile, CSVWriter.DEFAULT_SEPARATOR,
                 CSVWriter.NO_QUOTE_CHARACTER, CSVWriter.DEFAULT_ESCAPE_CHARACTER)) {
            List<String[]> upload_data = new ArrayList<>();
            String timeStamp = new SimpleDateFormat("MM-dd-yy hh:mmaaa", Locale.getDefault()).format(new Date());
            upload_data.add(new String[]{bar_string + "," + timeStamp});
            uploader.writeAll(upload_data);
            uploader.flush();

            // Temporarily hide the back button for visual feedback.
            binding.buttonBack.setVisibility(View.INVISIBLE);
            new Handler().postDelayed(() -> binding.buttonBack.setVisibility(View.VISIBLE), 3000);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to upload file", e);
        }
    }

    /**
     * Saves the scanned QR data into local preferences while avoiding duplicates.
     * Uses different keys based on the current upload mode.
     */
    private void saveData(String bar_string) {
        String uploadData;
        String uploadLines;

        // Determine the keys based on upload mode.
        switch (configPreference.getString("uploadMode", "Crowd")) {
            case "Specialty":
                uploadData = "special_upload_data";
                uploadLines = "special_seen_lines";
                break;
            case "Pit":
                uploadData = "pit_upload_data";
                uploadLines = "pit_seen_lines";
                break;
            case "Crowd":
            default:
                uploadData = "upload_data";
                uploadLines = "seen_lines";
                break;
        }

        // Retrieve the current raw data list.
        List<String[]> raw_data = matchPreference.getObject(uploadData, ArrayList.class, new ArrayList<>());
        String[] split = bar_string.split(",");
        raw_data.add(split);

        // Write the scanned data to a CSV file for debugging.
        makeUploadFile(bar_string);

        // Prevent duplicate entries using a Set.
        Set<String> seenLines = listPreference.getObject(uploadLines, Set.class, new HashSet<>());
        if (!seenLines.contains(bar_string) && !bar_string.contains("Role")) {
            seenLines.add(bar_string);
            listPreference.setObject(uploadLines, seenLines);
            matchPreference.setObject(uploadData, raw_data);
        } else {
            // Show a Toast if duplicate data is detected.
            Toast.makeText(requireContext(), "Already Exists", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Sets the background tint for a given team view.
     */
    private void set_team(int id) {
        TextView text = requireView().findViewById(id);
        text.setBackgroundTintList(getResources().getColorStateList(R.color.green_900, null));
    }

    /**
     * Updates the team display using the current match information.
     */
    private void setupTeamDisplay(int match) {
        int[] teamIds = new int[]{R.id.blue1, R.id.blue2, R.id.blue3, R.id.red1, R.id.red2, R.id.red3};
        for (int i = 0; i < teamIds.length; i++) {
            TextView team = requireView().findViewById(teamIds[i]);
            team.setText(teamInfo.getMasterTeam(match, i + 1));
            if (i < 3) {
                team.setBackgroundTintList(getResources().getColorStateList(android.R.color.holo_blue_light, null));
            } else {
                team.setBackgroundTintList(getResources().getColorStateList(android.R.color.holo_red_light, null));
            }
        }
    }

    /**
     * Processes a role QR code and updates configuration accordingly.
     */
    private void process_qr(String raw_qr) {
        NavController controller = NavHostFragment.findNavController(Scanner.this);
        String[] qr_data = raw_qr.split(",");
        try {
            String role = qr_data[1];
            int crowd_num = Integer.parseInt(qr_data[3]);
            String name = qr_data[5];
            boolean locked = Boolean.parseBoolean(qr_data[7]);
            int match = Integer.parseInt(qr_data[9]);
            boolean delete_data = Boolean.parseBoolean(qr_data[11]);
            boolean special_selector = Boolean.parseBoolean(qr_data[13]);

            if (delete_data) {
                // Clear all data if requested.
                PowerPreference.clearAllData();
            }

            configPreference.setString("device_role", role);
            configPreference.setInt("crowd_position", crowd_num);
            configPreference.setString("current_scouter", name);
            configPreference.setBoolean("role_locked_toggle", locked);
            configPreference.setBoolean("specialSwitch", special_selector);
        } catch (Exception e) {
            Log.e(TAG, "Error processing role QR code", e);
        }
    }

    /**
     * Restarts the application.
     */
    public static void restartApp(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(context.getPackageName());
        assert intent != null;
        ComponentName componentName = intent.getComponent();
        Intent mainIntent = Intent.makeRestartActivityTask(componentName);
        context.startActivity(mainIntent);
        Runtime.getRuntime().exit(0);
    }

    /**
     * Initiates the Sheets update process.
     */
    private void call_sheets() {
        SheetsUpdateTask taskRunner = new SheetsUpdateTask(requireActivity());
        taskRunner.execute();
    }

    /**
     * Refreshes the action bar with a title and subtitle.
     */
    public void refreshActionBar() {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null) {
            ActionBar actionBar = activity.getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle("Scanner");
                actionBar.setSubtitle("");
            }
        }
    }

    /**
     * Refreshes UI elements based on the device role.
     */
    private void refreshUI() {
        if (!debugPreference.getBoolean("isMaster", false)) {
            binding.teamListDisplay.getRoot().setVisibility(View.GONE);
            binding.uiInsideNumberPicker.setVisibility(View.GONE);
            binding.scanPrompt.setText(R.string.role_qr_title);
            binding.scanPrompt.setVisibility(View.VISIBLE);
            binding.buttonBack.setText(R.string.back);
            binding.matchSelectorText.setVisibility(View.GONE);
            binding.buttonUpload.setVisibility(View.GONE);
            binding.buttonGroupUploadMode.setVisibility(View.GONE);
        } else {
            binding.teamListDisplay.getRoot().setVisibility(View.VISIBLE);
            binding.uiInsideNumberPicker.setVisibility(View.VISIBLE);
            binding.scanPrompt.setVisibility(View.GONE);
            binding.buttonBack.setText(R.string.back);
            binding.matchSelectorText.setVisibility(View.VISIBLE);
            binding.buttonUpload.setVisibility(View.VISIBLE);
            binding.buttonGroupUploadMode.setVisibility(View.VISIBLE);
        }
    }
}
