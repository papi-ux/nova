package com.papi.nova;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.papi.nova.binding.PlatformBinding;
import com.papi.nova.binding.crypto.AndroidCryptoProvider;
import com.papi.nova.computers.ComputerManagerService;
import com.papi.nova.grid.PcGridAdapter;
import com.papi.nova.grid.assets.DiskAssetLoader;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import java.util.List;
import java.util.ArrayList;
import com.papi.nova.nvstream.http.PairingManager.PairState;

import androidx.lifecycle.ViewModelProvider;
import com.papi.nova.nvstream.wol.WakeOnLanSender;
import com.papi.nova.preferences.AddComputerManually;
import com.papi.nova.preferences.GlPreferences;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.preferences.StreamSettings;
import com.papi.nova.profiles.ProfilesManager;
import com.papi.nova.ui.AdapterFragment;
import com.papi.nova.ui.SpaceParticleView;
import com.papi.nova.ui.AdapterFragmentCallbacks;
import com.papi.nova.utils.Dialog;
import com.papi.nova.utils.HelpLauncher;
import com.papi.nova.utils.ServerHelper;
import com.papi.nova.utils.ShortcutHelper;
import com.papi.nova.utils.UiHelper;

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
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.res.ColorStateList;

import android.util.TypedValue;

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

import com.papi.nova.PcViewModel.ComputerObject;

import androidx.core.content.ContextCompat;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private static final int FILTER_ALL = 0;
    private static final int FILTER_ONLINE = 1;
    private static final int FILTER_STREAMING = 2;
    private static final int FILTER_NEEDS_PAIRING = 3;

    private View noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private PcViewModel viewModel;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled, autoNavigated;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
    private int currentServerFilter = FILTER_ALL;

    private void clearPendingPairing() {
        pendingPairingAddress = null;
        pendingPairingPin = null;
        pendingPairingPassphrase = null;
    }

    private boolean matchesPendingPairingAddress(ComputerDetails.AddressTuple address) {
        return address != null &&
                pendingPairingAddress != null &&
                address.port == pendingPairingAddress.port &&
                address.address.equalsIgnoreCase(pendingPairingAddress.address);
    }

    private void maybeRunPendingQrPairing(List<ComputerObject> computers) {
        if (pendingPairingAddress == null || pendingPairingPin == null || pendingPairingPassphrase == null) {
            return;
        }

        for (ComputerObject computer : computers) {
            ComputerDetails details = computer.details;
            if (details.state != ComputerDetails.State.ONLINE) {
                continue;
            }

            boolean matchesPendingHost =
                    matchesPendingPairingAddress(details.manualAddress) ||
                    matchesPendingPairingAddress(details.activeAddress) ||
                    matchesPendingPairingAddress(details.localAddress) ||
                    matchesPendingPairingAddress(details.remoteAddress) ||
                    matchesPendingPairingAddress(details.ipv6Address);

            if (!matchesPendingHost) {
                continue;
            }

            if (details.pairState == PairState.PAIRED) {
                clearPendingPairing();
                return;
            }

            String otp = pendingPairingPin;
            String passphrase = pendingPairingPassphrase;
            clearPendingPairing();
            doPair(details, otp, passphrase);
            return;
        }
    }

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
                    viewModel.startPolling(localBinder);

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
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
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

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Apply edge-to-edge insets to header (null-safe for landscape layouts)
        View header = findViewById(R.id.pcViewHeader);
        if (header != null) {
            header.setOnApplyWindowInsetsListener((v, insets) -> {
                int topInset = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    topInset = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    topInset = insets.getSystemWindowInsetTop();
                }
                v.setPadding(v.getPaddingLeft(), topInset + (int) UiHelper.dpToPx(this, 16),
                        v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            header.requestApplyInsets();
        }

        // Pull-to-refresh for server discovery
        spaceParticleView = findViewById(R.id.space_particles);
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.nova_accent));
            swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.nova_bg_elevated));
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

        // Setup the main actions (null-safe for alternate layouts)
        TextView modeServers = findViewById(R.id.modeServers);
        TextView modeLibrary = findViewById(R.id.modeLibrary);
        TextView addServerAction = findViewById(R.id.actionAddServer);
        TextView scanPairAction = findViewById(R.id.actionScanPair);
        TextView themeAction = findViewById(R.id.actionTheme);
        TextView settingsAction = findViewById(R.id.actionSettings);
        TextView helpAction = findViewById(R.id.actionHelp);
        TextView emptyAddServer = findViewById(R.id.emptyAddServer);
        TextView emptyScanPair = findViewById(R.id.emptyScanPair);
        TextView emptyHelp = findViewById(R.id.emptyHelp);
        TextView filterAllServers = findViewById(R.id.filterAllServers);
        TextView filterOnlineServers = findViewById(R.id.filterOnlineServers);
        TextView filterStreamingServers = findViewById(R.id.filterStreamingServers);
        TextView filterNeedsPairingServers = findViewById(R.id.filterNeedsPairingServers);
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);

        if (modeServers != null) {
            modeServers.setOnClickListener(v -> updateModeTabs());
        }
        if (modeLibrary != null) {
            modeLibrary.setOnClickListener(v -> launchQuickLibrary());
        }
        if (addServerAction != null) {
            addServerAction.setOnClickListener(v -> startActivity(new Intent(PcView.this, AddComputerManually.class)));
        }
        if (scanPairAction != null) {
            scanPairAction.setOnClickListener(v -> launchQrScanner());
        }
        if (themeAction != null) {
            themeAction.setOnClickListener(v -> cycleTheme());
        }
        if (settingsAction != null) {
            settingsAction.setOnClickListener(v -> {
                startActivity(new Intent(PcView.this, StreamSettings.class));
                com.papi.nova.ui.NovaThemeManager.INSTANCE.applyFadeTransition(PcView.this);
            });
        }
        if (helpAction != null) {
            helpAction.setOnClickListener(v -> HelpLauncher.launchSetupGuide(PcView.this));
        }
        if (emptyAddServer != null) {
            emptyAddServer.setOnClickListener(v -> startActivity(new Intent(PcView.this, AddComputerManually.class)));
        }
        if (emptyScanPair != null) {
            emptyScanPair.setOnClickListener(v -> launchQrScanner());
        }
        if (emptyHelp != null) {
            emptyHelp.setOnClickListener(v -> HelpLauncher.launchSetupGuide(PcView.this));
        }
        if (profilesButton != null) {
            profilesButton.setOnClickListener(v -> startActivity(new Intent(PcView.this, ProfilesActivity.class)));
        }

        if (filterAllServers != null) {
            filterAllServers.setOnClickListener(v -> setServerFilter(FILTER_ALL));
        }
        if (filterOnlineServers != null) {
            filterOnlineServers.setOnClickListener(v -> setServerFilter(FILTER_ONLINE));
        }
        if (filterStreamingServers != null) {
            filterStreamingServers.setOnClickListener(v -> setServerFilter(FILTER_STREAMING));
        }
        if (filterNeedsPairingServers != null) {
            filterNeedsPairingServers.setOnClickListener(v -> setServerFilter(FILTER_NEEDS_PAIRING));
        }

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            if (helpAction != null) {
                helpAction.setVisibility(View.GONE);
            }
            if (emptyHelp != null) {
                emptyHelp.setVisibility(View.GONE);
            }
        }

        applyThemeToServerBrowser();
        updateModeTabs();
        updateServerFilterTabs();
        syncComputerList();

        getSupportFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        updateEmptyState();
    }

    private void applyThemeToServerBrowser() {
        int accent = com.papi.nova.ui.NovaThemeManager.INSTANCE.getAccentColor(this);
        int textPrimary = com.papi.nova.ui.NovaThemeManager.INSTANCE.getTextPrimaryColor(this);
        int textMuted = com.papi.nova.ui.NovaThemeManager.INSTANCE.getTextMutedColor(this);
        int surface = com.papi.nova.ui.NovaThemeManager.INSTANCE.getCardBackgroundColor(this);

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh = findViewById(R.id.swipe_refresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(accent);
            swipeRefresh.setProgressBackgroundColorSchemeColor(surface);
        }

        TextView titleView = findViewById(R.id.pcViewTitle);
        if (titleView != null) {
            titleView.setTextColor(textPrimary);
        }

        TextView sectionView = findViewById(R.id.pcViewSectionLabel);
        if (sectionView != null) {
            sectionView.setTextColor(textMuted);
        }

        TextView emptyTitle = findViewById(R.id.pcViewEmptyTitle);
        if (emptyTitle != null) {
            emptyTitle.setTextColor(textMuted);
        }

        TextView emptyHint = findViewById(R.id.pcViewEmptyHint);
        if (emptyHint != null) {
            emptyHint.setTextColor(textMuted);
        }

        tintChipRow(new int[] {
                R.id.actionAddServer,
                R.id.actionScanPair,
                R.id.actionTheme,
                R.id.actionSettings,
                R.id.actionHelp,
                R.id.emptyAddServer,
                R.id.emptyScanPair,
                R.id.emptyHelp
        }, textPrimary);

        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        if (profilesButton != null) {
            profilesButton.setBackgroundTintList(ColorStateList.valueOf(accent));
            profilesButton.setIconTint(ColorStateList.valueOf(textPrimary));
            profilesButton.setTextColor(textPrimary);
        }
    }

    private void tintChipRow(int[] ids, int color) {
        for (int id : ids) {
            TextView chip = findViewById(id);
            if (chip != null) {
                chip.setTextColor(color);
            }
        }
    }

    private void cycleTheme() {
        String nextTheme = com.papi.nova.ui.NovaThemeManager.INSTANCE.cycleTheme(PcView.this);
        Toast.makeText(
                PcView.this,
                getString(R.string.nova_theme_switched_to, com.papi.nova.ui.NovaThemeManager.INSTANCE.getThemeLabel(PcView.this, nextTheme)),
                Toast.LENGTH_SHORT
        ).show();
        recreate();
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyFadeTransition(PcView.this);
    }

    private void updateModeTabs() {
        // Material chips handle their own background states via selector now
        com.google.android.material.chip.ChipGroup group = findViewById(R.id.pcModeTabs);
        if (group != null) {
            group.check(R.id.modeServers);
        }
    }

    private void updateServerFilterTabs() {
        int selectedId = switch (currentServerFilter) {
            case FILTER_ONLINE -> R.id.filterOnlineServers;
            case FILTER_STREAMING -> R.id.filterStreamingServers;
            case FILTER_NEEDS_PAIRING -> R.id.filterNeedsPairingServers;
            default -> R.id.filterAllServers;
        };

        com.google.android.material.chip.ChipGroup group = findViewById(R.id.serverFilterTabs);
        if (group != null) {
            group.check(selectedId);
        }
    }

    private void setServerFilter(int filter) {
        if (currentServerFilter == filter) {
            return;
        }

        currentServerFilter = filter;
        updateServerFilterTabs();
        syncComputerList();
    }

    private boolean matchesCurrentFilter(ComputerObject computer) {
        return switch (currentServerFilter) {
            case FILTER_ONLINE -> computer.details.state != ComputerDetails.State.OFFLINE;
            case FILTER_STREAMING -> computer.details.runningGameId != 0;
            case FILTER_NEEDS_PAIRING -> computer.details.pairState != PairState.PAIRED;
            default -> true;
        };
    }

    private void syncComputerList() {
        if (pcGridAdapter == null || viewModel == null) {
            return;
        }

        List<ComputerObject> allComputers = viewModel.getComputersLiveData().getValue();
        if (allComputers == null) {
            return;
        }

        ArrayList<ComputerObject> visibleComputers = new ArrayList<>();
        int pairedOnlineCount = 0;
        ComputerObject singleServer = null;

        for (ComputerObject computer : allComputers) {
            if (matchesCurrentFilter(computer)) {
                visibleComputers.add(computer);
                if (computer.details.state != ComputerDetails.State.OFFLINE && computer.details.pairState == PairState.PAIRED) {
                    pairedOnlineCount++;
                    singleServer = computer;
                }
            }
        }

        pcGridAdapter.setItems(visibleComputers);
        updateEmptyState();

        if (!autoNavigated && pairedOnlineCount == 1) {
            autoNavigated = true;
            doAppList(singleServer.details, false, false);
        }
    }

    private void updateEmptyState() {
        if (noPcFoundLayout == null || viewModel == null) {
            return;
        }

        TextView emptyTitle = findViewById(R.id.pcViewEmptyTitle);
        TextView emptyHint = findViewById(R.id.pcViewEmptyHint);

        List<ComputerObject> computers = viewModel.getComputersLiveData().getValue();
        if (computers == null || computers.isEmpty()) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
            if (emptyTitle != null) {
                emptyTitle.setText(runningPolling ? R.string.pcview_empty_title_searching : R.string.pcview_empty_title_no_servers);
            }
            if (emptyHint != null) {
                emptyHint.setText(runningPolling ? R.string.pcview_empty_hint_searching : R.string.pcview_empty_hint_no_servers);
            }
            return;
        }

        if (pcGridAdapter.getItemCount() > 0) {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
            return;
        }

        noPcFoundLayout.setVisibility(View.VISIBLE);
        if (emptyTitle == null || emptyHint == null) {
            return;
        }

        switch (currentServerFilter) {
            case FILTER_ONLINE -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_online);
                emptyHint.setText(R.string.pcview_empty_hint_no_online);
            }
            case FILTER_STREAMING -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_streaming);
                emptyHint.setText(R.string.pcview_empty_hint_no_streaming);
            }
            case FILTER_NEEDS_PAIRING -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_pairing);
                emptyHint.setText(R.string.pcview_empty_hint_no_pairing);
            }
            default -> {
                emptyTitle.setText(R.string.pcview_empty_title_no_servers);
                emptyHint.setText(R.string.pcview_empty_hint_no_servers);
            }
        }
    }

    private void launchQuickLibrary() {
        java.util.ArrayList<ComputerObject> candidates = new java.util.ArrayList<>();
        List<ComputerObject> allComputers = viewModel.getComputersLiveData().getValue();
        if (allComputers != null) {
            for (ComputerObject candidate : allComputers) {
                if (candidate.details.state == ComputerDetails.State.ONLINE &&
                        candidate.details.pairState == PairState.PAIRED &&
                        candidate.details.activeAddress != null) {
                    candidates.add(candidate);
                }
            }
        }

        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.pcview_library_no_server, Toast.LENGTH_SHORT).show();
            return;
        }

        if (candidates.size() == 1) {
            doNovaLibrary(candidates.get(0).details);
            return;
        }

        CharSequence[] names = new CharSequence[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).details.name;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pcview_library_choose_server)
                .setItems(names, (dialog, which) -> doNovaLibrary(candidates.get(which).details))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String appliedTheme;

    private SpaceParticleView spaceParticleView;

    @NonNull
    private GLSurfaceView getGlSurfaceView(GlPreferences glPrefs) {
        GLSurfaceView surfaceView = new GLSurfaceView(this);
        surfaceView.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                // Save the GLRenderer string so we don't need to do this next time
                glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                glPrefs.savedFingerprint = Build.FINGERPRINT;
                glPrefs.writePreferences();

                LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                runOnUiThread(PcView.this::completeOnCreate);
            }

            @Override
            public void onSurfaceChanged(GL10 gl10, int i, int i1) {
            }

            @Override
            public void onDrawFrame(GL10 gl10) {
            }
        });
        return surfaceView;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
            GLSurfaceView surfaceView = getGlSurfaceView(glPrefs);
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
            clearPendingPairing();
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

        viewModel = new ViewModelProvider(this).get(PcViewModel.class);
        viewModel.getComputersLiveData().observe(this, newList -> {
            if (!freezeUpdates) {
                pcGridAdapter.setItems(newList);
                updateEmptyState();
                maybeRunPendingQrPairing(newList);

                // Auto-navigate logic
                checkAutoNavigation(newList);
            }
        });

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            viewModel.startPolling(managerBinder);

            runningPolling = true;
            updateEmptyState();
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;
            viewModel.stopPolling(managerBinder);

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
            updateEmptyState();
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
            recreate();
            return;
        }

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        inForeground = true;
        if (spaceParticleView != null) spaceParticleView.resume();
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        if (spaceParticleView != null) spaceParticleView.pause();
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
            String status = switch (computer.details.state) {
                case ONLINE -> getString(R.string.pcview_menu_header_online);
                case OFFLINE -> getString(R.string.pcview_menu_header_offline);
                default -> getString(R.string.pcview_menu_header_unknown);
            };
            titleView.setText(getString(R.string.pcview_menu_header_format, computer.details.name, status));
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
            // "Pair PC" attempts TOFU first if supported, then falls back to PIN pairing
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc), () -> {
                sheet.dismiss();
                doPair(computer.details, null, null);
            });
            addPcSheetAction(actions, getString(R.string.pcview_menu_pair_pc_otp), () -> {
                sheet.dismiss();
                doOTPPair(computer.details);
            });
            addPcSheetAction(actions, getString(R.string.pcview_menu_scan_qr), () -> {
                sheet.dismiss();
                launchQrScanner();
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
                    UiHelper.displayQuitConfirmationDialog(this, () -> ServerHelper.doQuit(this, computer.details, runningApp, managerBinder, null), null);
                });
            }
            addPcSheetAction(actions, getString(R.string.pcview_menu_app_list), () -> {
                sheet.dismiss();
                doAppList(computer.details, false, false);
            });
            addPcSheetAction(actions, getString(R.string.pcview_menu_nova_library), () -> {
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
        deleteItem.setTextColor(ContextCompat.getColor(this, R.color.nova_error));
        int pad = (int) UiHelper.dpToPx(this, 24);
        int padV = (int) UiHelper.dpToPx(this, 14);
        deleteItem.setPadding(pad, padV, pad, padV);
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        deleteItem.setBackgroundResource(outValue.resourceId);
        deleteItem.setOnClickListener(v -> {
            sheet.dismiss();
            UiHelper.displayDeletePcConfirmationDialog(this, computer.details, () -> removeComputer(computer.details), null);
        });
        actions.addView(deleteItem);

        sheet.show();
    }

    private void addPcSheetAction(android.widget.LinearLayout container, String label, Runnable action) {
        android.widget.TextView item = new android.widget.TextView(this);
        item.setText(label);
        item.setTextSize(15);
        item.setTextColor(ContextCompat.getColor(this, R.color.nova_text_primary));
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
        new Thread(() -> {
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
                                        PcView.this, "Server doesn’t support TOFU — update Polaris or use OTP/PIN"));
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
                    LimeLog.warning(e.toString());
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(() -> {
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
                });
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

        new Thread(() -> {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String snackMessage = message;
                runOnUiThread(() -> com.papi.nova.ui.NovaSnackbar.INSTANCE.show(PcView.this, snackMessage));
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
        i.putExtra(com.papi.nova.ui.NovaLibraryActivity.EXTRA_SERVER_NAME, computer.name);
        i.putExtra(com.papi.nova.ui.NovaLibraryActivity.EXTRA_HTTPS_PORT, computer.httpsPort);
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

        shortcutHelper.disableComputerShortcut(details,
                getResources().getString(R.string.scut_deleted_pc));

        syncComputerList();
    }

    // Obsolete UUID-keyed map removed (now managed by ViewModel)
    // private final java.util.HashMap<String, ComputerObject> computerMap = ...;

    private void checkAutoNavigation(List<ComputerObject> computers) {
        boolean autoConnectEnabled = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this).getBoolean("nova_auto_connect", true);
        if (autoConnectEnabled && !autoNavigated && pendingPairingAddress == null) {
            int pairedOnlineCount = 0;
            ComputerObject singleServer = null;
            for (ComputerObject c : computers) {
                if (c.details.state == ComputerDetails.State.ONLINE &&
                    c.details.pairState == PairState.PAIRED) {
                    pairedOnlineCount++;
                    singleServer = c;
                }
            }
            if (pairedOnlineCount == 1) {
                autoNavigated = true;
                final ComputerObject target = singleServer;
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
    public void receiveAbsListView(View gridView) {
        if (gridView instanceof androidx.recyclerview.widget.RecyclerView rv) {
            rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 1));
            rv.setAdapter(pcGridAdapter);
            pcGridAdapter.setOnItemClickListener(computer -> {
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    showServerBottomSheet(computer);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    showServerBottomSheet(computer);
                } else {
                    doAppList(computer.details, false, false);
                }
            });
            // Long click handler
            rv.addOnItemTouchListener(new com.papi.nova.grid.RecyclerItemClickListener(this, rv, new com.papi.nova.grid.RecyclerItemClickListener.OnItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {}
                
                @Override
                public void onLongItemClick(View view, int position) {
                    ComputerObject computer = pcGridAdapter.getItem(position);
                    showServerBottomSheet(computer);
                }
            }));
            UiHelper.applyStatusBarPadding(rv);
        }
    }
}
