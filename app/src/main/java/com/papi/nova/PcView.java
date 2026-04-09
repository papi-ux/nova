package com.papi.nova;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.papi.nova.LimeLog;
import com.papi.nova.binding.PlatformBinding;
import com.papi.nova.binding.crypto.AndroidCryptoProvider;
import com.papi.nova.computers.ComputerManagerListener;
import com.papi.nova.computers.ComputerManagerService;
import com.papi.nova.grid.PcGridAdapter;
import com.papi.nova.grid.assets.DiskAssetLoader;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.nvstream.http.PairingManager.PairState;
import com.papi.nova.nvstream.wol.WakeOnLanSender;
import com.papi.nova.preferences.AddComputerManually;
import com.papi.nova.preferences.GlPreferences;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.preferences.StreamSettings;
import com.papi.nova.profiles.ProfilesManager;
import com.papi.nova.ui.AdapterFragment;
import com.papi.nova.ui.AdapterFragmentCallbacks;
import com.papi.nova.utils.Dialog;
import com.papi.nova.utils.HelpLauncher;
import com.papi.nova.utils.ServerHelper;
import com.papi.nova.utils.ShortcutHelper;
import com.papi.nova.utils.UiHelper;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import android.util.TypedValue;
import android.widget.LinearLayout.LayoutParams;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import androidx.activity.result.ActivityResultLauncher;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private View noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled, autoNavigated;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;

    private final ActivityResultLauncher<ScanOptions> qrScanLauncher = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() != null) {
                    handleQrScanResult(result.getContents());
                }
            });
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

        refreshProfileButton();
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int PAIR_ID_OTP = 21;
    private final static int NOVA_LIBRARY_ID = 22;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Apply edge-to-edge insets to header (null-safe for landscape layouts)
        View header = findViewById(R.id.pcViewHeader);
        if (header != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            header.setOnApplyWindowInsetsListener((v, insets) -> {
                int topInset = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    topInset = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    topInset = insets.getSystemWindowInsetTop();
                }
                v.setPadding(v.getPaddingLeft(), topInset + (int) UiHelper.dpToPx(this, 16),
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            header.requestApplyInsets();
        }

        // Pull-to-refresh for server discovery
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(getColor(R.color.nova_accent));
            swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.nova_bg_elevated));
            swipeRefresh.setOnRefreshListener(() -> {
                // Restart computer polling to force a fresh discovery
                stopComputerUpdates(false);
                startComputerUpdates();
                // Auto-dismiss after a short delay
                swipeRefresh.postDelayed(() -> swipeRefresh.setRefreshing(false), 2000);
            });
        }

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view (null-safe for alternate layouts)
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);

        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> {
                startActivity(new Intent(PcView.this, StreamSettings.class));
                com.papi.nova.ui.NovaThemeManager.INSTANCE.applyFadeTransition(PcView.this);
            });
        }
        if (addComputerButton != null) {
            addComputerButton.setOnClickListener(v -> {
                startActivity(new Intent(PcView.this, AddComputerManually.class));
            });
        }
        ImageButton scanQrButton = findViewById(R.id.scanQrButton);
        if (scanQrButton != null) {
            scanQrButton.setOnClickListener(v -> launchQrScanner());
        }
        if (helpButton != null) {
            helpButton.setOnClickListener(v -> HelpLauncher.launchSetupGuide(PcView.this));
        }
        if (profilesButton != null) {
            profilesButton.setOnClickListener(v -> startActivity(new Intent(PcView.this, ProfilesActivity.class)));
        }

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (helpButton != null && getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    private String appliedTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Nova theme before inflating views
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyTheme(this);
        appliedTheme = com.papi.nova.ui.NovaThemeManager.INSTANCE.getTheme(this);
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        // Show welcome screen on first launch
        if (com.papi.nova.ui.NovaWelcomeActivity.Companion.shouldShow(this)) {
            startActivity(new Intent(this, com.papi.nova.ui.NovaWelcomeActivity.class));
        }

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    private void refreshProfileButton() {
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Restart if theme changed while in settings
        String currentTheme = com.papi.nova.ui.NovaThemeManager.INSTANCE.getTheme(this);
        if (appliedTheme != null && !appliedTheme.equals(currentTheme)) {
            appliedTheme = currentTheme;
            android.content.Intent intent = getIntent();
            finish();
            startActivity(intent);
            return;
        }

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    private void showServerBottomSheet(ComputerObject computer) {
        stopComputerUpdates(false);

        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.NovaBottomSheet);
        sheet.setContentView(R.layout.nova_app_context_sheet);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);

        sheet.setOnDismissListener(d -> startComputerUpdates());

        android.widget.TextView titleView = sheet.findViewById(R.id.sheet_app_name);
        if (titleView != null) {
            String status;
            switch (computer.details.state) {
                case ONLINE: status = getString(R.string.pcview_menu_header_online); break;
                case OFFLINE: status = getString(R.string.pcview_menu_header_offline); break;
                default: status = getString(R.string.pcview_menu_header_unknown); break;
            }
            titleView.setText(computer.details.name + " \u00b7 " + status);
        }

        android.widget.LinearLayout actions = sheet.findViewById(R.id.sheet_actions);
        if (actions == null) { sheet.show(); return; }

        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            addPcSheetAction(actions, getString(R.string.pcview_menu_send_wol), () -> {
                sheet.dismiss();
                doWakeOnLan(computer.details);
            });
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            // TOFU: auto-pair via trusted subnet — doPair(null,null) triggers TOFU detection
            addPcSheetAction(actions, "Pair (Auto / TOFU)", () -> {
                sheet.dismiss();
                com.papi.nova.ui.NovaSnackbar.INSTANCE.show(this,
                        "Attempting auto-pair via trusted subnet\u2026");
                doPair(computer.details, null, null);
            });
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc_otp), () -> {
                sheet.dismiss();
                doOTPPair(computer.details);
            });
            addPcSheetAction(actions, "Pair via QR Code", () -> {
                sheet.dismiss();
                launchQrScanner();
            });
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc), () -> {
                sheet.dismiss();
                doPair(computer.details, null, null);
            });
            if (!computer.details.nvidiaServer) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_open_management_page), () -> {
                    sheet.dismiss();
                    String url = computer.guessManagementUrl();
                    if (url != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } else {
                        Toast.makeText(this, R.string.pcview_error_no_management_url, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                addPcSheetAction(actions, getString(R.string.applist_menu_resume), () -> {
                    sheet.dismiss();
                    NvApp runningApp = new NvApp();
                    runningApp.setAppId(computer.details.runningGameId);
                    ServerHelper.doStart(this, runningApp, computer.details, managerBinder, false);
                });
                addPcSheetAction(actions, getString(R.string.applist_menu_quit), () -> {
                    sheet.dismiss();
                    NvApp runningApp = new NvApp();
                    runningApp.setAppId(computer.details.runningGameId);
                    UiHelper.displayQuitConfirmationDialog(this, () -> {
                        ServerHelper.doQuit(this, computer.details, runningApp, managerBinder, null);
                    }, null);
                });
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_app_list), () -> {
                sheet.dismiss();
                doAppList(computer.details, false, false);
            });
            addPcSheetAction(actions, "Nova Library", () -> {
                sheet.dismiss();
                doNovaLibrary(computer.details);
            });
            if (!computer.details.nvidiaServer) {
                addPcSheetAction(actions, getString(R.string.pcview_menu_open_management_page), () -> {
                    sheet.dismiss();
                    String url = computer.guessManagementUrl();
                    if (url != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } else {
                        Toast.makeText(this, R.string.pcview_error_no_management_url, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        addPcSheetAction(actions, getString(R.string.pcview_menu_test_network), () -> {
            sheet.dismiss();
            ServerHelper.doNetworkTest(this);
        });
        addPcSheetAction(actions, getString(R.string.pcview_menu_details), () -> {
            sheet.dismiss();
            Dialog.displayDialog(this, getString(R.string.title_details), computer.details.toString(), false);
        });

        // Delete at the bottom — dangerous action
        android.widget.TextView deleteItem = new android.widget.TextView(this);
        deleteItem.setText(getString(R.string.pcview_menu_delete_pc));
        deleteItem.setTextSize(15);
        deleteItem.setTextColor(getColor(R.color.nova_error));
        int pad = (int) UiHelper.dpToPx(this, 24);
        int padV = (int) UiHelper.dpToPx(this, 14);
        deleteItem.setPadding(pad, padV, pad, padV);
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        deleteItem.setBackgroundResource(outValue.resourceId);
        deleteItem.setOnClickListener(v -> {
            sheet.dismiss();
            UiHelper.displayDeletePcConfirmationDialog(this, computer.details, () -> {
                removeComputer(computer.details);
            }, null);
        });
        actions.addView(deleteItem);

        sheet.show();
    }

    private void addPcSheetAction(android.widget.LinearLayout container, String label, Runnable action) {
        android.widget.TextView item = new android.widget.TextView(this);
        item.setText(label);
        item.setTextSize(15);
        item.setTextColor(getColor(R.color.nova_text_primary));
        int pad = (int) UiHelper.dpToPx(this, 24);
        int padV = (int) UiHelper.dpToPx(this, 14);
        item.setPadding(pad, padV, pad, padV);
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        item.setBackgroundResource(outValue.resourceId);
        item.setOnClickListener(v -> action.run());
        container.addView(item);
    }

    // Context menu replaced by showServerBottomSheet() above

    private void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.pcview_menu_scan_qr));
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        options.setCaptureActivity(com.papi.nova.ui.NovaQrScanActivity.class);
        qrScanLauncher.launch(options);
    }

    private void handleQrScanResult(String contents) {
        Uri uri = Uri.parse(contents);
        if (!"art".equals(uri.getScheme())) {
            Toast.makeText(this, "Invalid QR code — expected Polaris pairing code", Toast.LENGTH_SHORT).show();
            return;
        }

        String pin = uri.getQueryParameter("pin");
        String passphrase = uri.getQueryParameter("passphrase");
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : NvHTTP.DEFAULT_HTTP_PORT;

        if (pin == null || passphrase == null || host == null) {
            Toast.makeText(this, "QR code is missing pairing data", Toast.LENGTH_SHORT).show();
            return;
        }

        if (managerBinder == null) {
            Toast.makeText(this, getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // Set pending pairing credentials so auto-pair triggers when computer comes online
        pendingPairingPin = pin;
        pendingPairingPassphrase = passphrase;
        pendingPairingAddress = new ComputerDetails.AddressTuple(host, port);

        com.papi.nova.ui.NovaSnackbar.INSTANCE.show(this, "Connecting to " + host + "...");

        // Add the computer in background to trigger discovery + auto-pair
        new Thread(() -> {
            ComputerDetails details = new ComputerDetails();
            details.manualAddress = new ComputerDetails.AddressTuple(host, port);
            try {
                managerBinder.addComputerBlocking(details);
            } catch (InterruptedException e) {
                // Ignore
            }
        }).start();
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        com.papi.nova.ui.NovaSnackbar.INSTANCE.show(PcView.this, getResources().getString(R.string.pairing));
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        PairingManager pm = httpConn.getPairingManager();
                        String serverInfo = httpConn.getServerInfo(true);

                        // Try TOFU (Trust-on-First-Use) if the server supports it and this
                        // is not an explicit OTP pairing attempt
                        if (otp == null && passphrase == null) {
                            if (serverInfo.contains("<TofuEnabled>1</TofuEnabled>")) {
                                LimeLog.info("TOFU: Server supports trusted subnet pairing, attempting auto-pair");
                                PairState tofuState = pm.pair(serverInfo, "0000", null);
                                if (tofuState == PairState.PAIRED) {
                                    message = null;
                                    success = true;

                                    managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                                    managerBinder.invalidateStateForComputer(computer.uuid);

                                    Dialog.closeDialogs();
                                    runOnUiThread(() -> {
                                        com.papi.nova.ui.NovaSnackbar.INSTANCE.showSuccess(
                                                PcView.this, "Paired successfully via TOFU");
                                        doAppList(computer, true, false);
                                    });
                                    return;
                                }
                                // TOFU failed — show clear message and fall through
                                LimeLog.info("TOFU: Auto-pair failed, falling back to PIN pairing");
                                runOnUiThread(() -> com.papi.nova.ui.NovaSnackbar.INSTANCE.showError(
                                        PcView.this, "TOFU auto-pair failed — trying PIN pairing"));
                                serverInfo = httpConn.getServerInfo(true);
                            } else {
                                LimeLog.info("TOFU: Server does not advertise TofuEnabled — rebuild Polaris to enable");
                                runOnUiThread(() -> com.papi.nova.ui.NovaSnackbar.INSTANCE.showError(
                                        PcView.this, "Server doesn\u2019t support TOFU — update Polaris or use OTP/PIN"));
                            }
                        }

                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                            getResources().getString(R.string.pair_pairing_help), false);
                        } else {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_otp_pairing_msg)+"\n\n"+
                                            getResources().getString(R.string.pair_otp_pairing_help), false);
                        }

                        PairState pairState = pm.pair(serverInfo, pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doOTPPair(final ComputerDetails computer) {
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            com.papi.nova.ui.NovaSnackbar.INSTANCE.show(PcView.this, getResources().getString(R.string.wol_pc_online));
            return;
        }

        if (computer.macAddress == null) {
            com.papi.nova.ui.NovaSnackbar.INSTANCE.showError(PcView.this, getResources().getString(R.string.wol_no_mac));
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String snackMessage = message;
                runOnUiThread(() -> com.papi.nova.ui.NovaSnackbar.INSTANCE.show(PcView.this, snackMessage));
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyForwardTransition(this);
    }

    private void doNovaLibrary(ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(this, com.papi.nova.ui.NovaLibraryActivity.class);
        i.putExtra(com.papi.nova.ui.NovaLibraryActivity.EXTRA_HOST, computer.activeAddress.address);
        startActivity(i);
    }

    // onContextItemSelected removed — all actions handled by showServerBottomSheet()

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        ComputerObject computer = computerMap.remove(details.uuid);
        if (computer != null) {
            shortcutHelper.disableComputerShortcut(details,
                    getResources().getString(R.string.scut_deleted_pc));

            pcGridAdapter.removeComputer(computer);
            pcGridAdapter.notifyDataSetChanged();

            if (pcGridAdapter.getCount() == 0) {
                noPcFoundLayout.setVisibility(View.VISIBLE);
            }
        }
    }

    // UUID-keyed map for O(1) computer lookups (mirrors adapter list)
    private final java.util.HashMap<String, ComputerObject> computerMap = new java.util.HashMap<>();

    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = computerMap.get(details.uuid);

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            ComputerObject newObj = new ComputerObject(details);
            pcGridAdapter.addComputer(newObj);
            computerMap.put(details.uuid, newObj);

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();

        // Auto-navigate: if exactly 1 paired online server, go straight to app list
        boolean autoConnectEnabled = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this).getBoolean("nova_auto_connect", true);
        if (autoConnectEnabled && !autoNavigated && pendingPairingAddress == null) {
            int pairedOnlineCount = 0;
            ComputerObject singleServer = null;
            for (int i = 0; i < pcGridAdapter.getCount(); i++) {
                ComputerObject c = (ComputerObject) pcGridAdapter.getItem(i);
                if (c.details.state == ComputerDetails.State.ONLINE &&
                    c.details.pairState == PairState.PAIRED) {
                    pairedOnlineCount++;
                    singleServer = c;
                }
            }
            if (pairedOnlineCount == 1 && singleServer != null) {
                autoNavigated = true;
                final ComputerObject target = singleServer;
                // Brief delay so the UI renders before navigating
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (inForeground && !isFinishing()) {
                        doAppList(target.details, false, false);
                    }
                }, 400);
            }
        }
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                arg1.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Show bottom sheet if a PC is offline or refreshing
                    showServerBottomSheet(computer);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Show pairing options bottom sheet
                    showServerBottomSheet(computer);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);

        // Use Nova bottom sheet instead of stock context menu
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
            showServerBottomSheet(computer);
            return true;
        });
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}
