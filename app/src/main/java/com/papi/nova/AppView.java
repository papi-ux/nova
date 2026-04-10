package com.papi.nova;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.papi.nova.computers.ComputerManagerListener;
import com.papi.nova.computers.ComputerManagerService;
import com.papi.nova.grid.AppGridAdapter;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.profiles.ProfilesManager;
import com.papi.nova.ui.AdapterFragment;
import com.papi.nova.ui.AdapterFragmentCallbacks;
import com.papi.nova.utils.CacheHelper;
import com.papi.nova.utils.Dialog;
import com.papi.nova.utils.ServerHelper;
import com.papi.nova.utils.ShortcutHelper;
import com.papi.nova.utils.SpinnerDialog;
import com.papi.nova.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
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

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Load pinned games
                    HashSet<Integer> pinnedIds = new HashSet<>();
                    for (String s : getSharedPreferences("nova_prefs", MODE_PRIVATE)
                            .getStringSet("pinned_" + uuidString, new HashSet<>())) {
                        pinnedIds.add(Integer.parseInt(s));
                    }
                    appGridAdapter.updatePinnedApps(pinnedIds);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
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

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Nova theme before inflating views
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyTheme(this);
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Apply edge-to-edge insets to header (null-safe for landscape layouts)
        View header = findViewById(R.id.appListHeader);
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

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        // Game search bar
        android.widget.EditText searchBar = findViewById(R.id.app_search);
        if (searchBar != null) {
            searchBar.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (appGridAdapter != null) {
                        appGridAdapter.filterByName(s.toString());
                    }
                }
            });
        }

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Predictive back: apply back transition animation
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    public void finish() {
        super.finish();
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyBackTransition(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

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
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // Context menu replaced by showAppBottomSheet() — see bottom sheet implementation below

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getItemCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }

                // Update recently played hero card
                updateRecentlyPlayedCard();
            }
        });
    }

    private void updateRecentlyPlayedCard() {
        View card = findViewById(R.id.recently_played_card);
        if (card == null) return;

        // Find the running game or last played from prefs
        int targetAppId = lastRunningAppId;
        if (targetAppId == 0) {
            targetAppId = getSharedPreferences("nova_prefs", MODE_PRIVATE)
                    .getInt("last_played_" + uuidString, 0);
        }

        if (targetAppId == 0 || appGridAdapter == null) {
            card.setVisibility(View.GONE);
            return;
        }

        // Find the app in the adapter
        AppObject targetApp = null;
        for (int i = 0; i < appGridAdapter.getItemCount(); i++) {
            AppObject a = (AppObject) appGridAdapter.getItem(i);
            if (a.app.getAppId() == targetAppId) {
                targetApp = a;
                break;
            }
        }

        if (targetApp == null) {
            card.setVisibility(View.GONE);
            return;
        }

        card.setVisibility(View.VISIBLE);
        TextView nameView = findViewById(R.id.recently_played_name);
        if (nameView != null) nameView.setText(targetApp.app.getAppName());

        final AppObject finalApp = targetApp;
        card.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            // Save as last played
            getSharedPreferences("nova_prefs", MODE_PRIVATE).edit()
                    .putInt("last_played_" + uuidString, finalApp.app.getAppId())
                    .apply();
            ServerHelper.doStart(this, finalApp.app, computer, managerBinder, prefConfig.useVirtualDisplay);
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // Build a map of incoming apps by ID for O(1) lookup
                java.util.HashMap<Integer, NvApp> incomingMap = new java.util.HashMap<>(appList.size());
                for (NvApp app : appList) {
                    incomingMap.put(app.getAppId(), app);
                }

                // Build a map of existing apps by ID
                java.util.HashMap<Integer, AppObject> existingMap = new java.util.HashMap<>(appGridAdapter.getItemCount());
                for (int i = 0; i < appGridAdapter.getItemCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                    existingMap.put(existingApp.app.getAppId(), existingApp);
                }

                // Handle updates and additions (single pass over incoming list)
                for (NvApp app : appList) {
                    AppObject existing = existingMap.get(app.getAppId());
                    if (existing != null) {
                        // Update name if changed
                        if (!existing.app.getAppName().equals(app.getAppName())) {
                            existing.app.setAppName(app.getAppName());
                            updated = true;
                        }
                    } else {
                        // New app
                        appGridAdapter.addApp(new AppObject(app));
                        shortcutHelper.enableAppShortcut(computer, app);
                        updated = true;
                    }
                }

                // Handle removals (single pass over existing list)
                for (java.util.Map.Entry<Integer, AppObject> entry : existingMap.entrySet()) {
                    if (!incomingMap.containsKey(entry.getKey())) {
                        shortcutHelper.disableAppShortcut(computer, entry.getValue().app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(entry.getValue());
                        updated = true;
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();

                    // Show search bar when there are enough games
                    android.widget.EditText searchView = findViewById(R.id.app_search);
                    if (searchView != null && appGridAdapter.getTotalAppCount() > 6) {
                        searchView.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
    }

    private void showAppBottomSheet(AppObject selectedApp, int position) {
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.NovaBottomSheet);
        sheet.setContentView(R.layout.nova_app_context_sheet);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.getBehavior().setSkipCollapsed(true);

        TextView titleView = sheet.findViewById(R.id.sheet_app_name);
        if (titleView != null) titleView.setText(selectedApp.app.getAppName());

        LinearLayout actions = sheet.findViewById(R.id.sheet_actions);
        if (actions == null) { sheet.show(); return; }

        // Build actions based on app state
        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                addSheetAction(actions, getString(R.string.applist_menu_start_primarydisplay), () -> {
                    sheet.dismiss();
                    ServerHelper.doStart(this, selectedApp.app, computer, managerBinder, false);
                });
            } else {
                addSheetAction(actions, getString(R.string.applist_menu_start_vdisplay), () -> {
                    sheet.dismiss();
                    boolean vdReady = computer.vDisplaySupported && computer.vDisplayDriverReady;
                    if (!vdReady) {
                        UiHelper.displayVdisplayConfirmationDialog(this, computer,
                            () -> ServerHelper.doStart(this, selectedApp.app, computer, managerBinder, true), null);
                    } else {
                        ServerHelper.doStart(this, selectedApp.app, computer, managerBinder, true);
                    }
                });
            }
        } else if (lastRunningAppId == selectedApp.app.getAppId()) {
            addSheetAction(actions, getString(R.string.applist_menu_resume), () -> {
                sheet.dismiss();
                ServerHelper.doStart(this, selectedApp.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            });
            addSheetAction(actions, getString(R.string.applist_menu_quit), () -> {
                sheet.dismiss();
                UiHelper.displayQuitConfirmationDialog(this, () -> {
                    suspendGridUpdates = true;
                    ServerHelper.doQuit(this, computer, selectedApp.app, managerBinder, () -> {
                        suspendGridUpdates = false;
                        if (poller != null) poller.pollNow();
                    });
                }, null);
            });
        } else {
            String startLabel = getString(R.string.applist_menu_quit_and_start);
            addSheetAction(actions, startLabel, () -> {
                sheet.dismiss();
                UiHelper.displayQuitConfirmationDialog(this, () ->
                    ServerHelper.doStart(this, selectedApp.app, computer, managerBinder, prefConfig.useVirtualDisplay), null);
            });
        }

        // Hide/show toggle
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            String hideLabel = getString(R.string.applist_menu_hide_app) +
                    (selectedApp.isHidden ? " ✓" : "");
            addSheetAction(actions, hideLabel, () -> {
                sheet.dismiss();
                if (selectedApp.isHidden) {
                    hiddenAppIds.remove(selectedApp.app.getAppId());
                } else {
                    hiddenAppIds.add(selectedApp.app.getAppId());
                }
                updateHiddenApps(false);
            });
        }

        // Pin/Unpin toggle
        boolean isPinned = appGridAdapter.isAppPinned(selectedApp.app.getAppId());
        addSheetAction(actions, isPinned ? "Unpin from Top" : "Pin to Top", () -> {
            sheet.dismiss();
            Set<String> pinnedStrSet = getSharedPreferences("nova_prefs", MODE_PRIVATE)
                    .getStringSet("pinned_" + uuidString, new HashSet<>());
            HashSet<Integer> pinnedIds = new HashSet<>();
            for (String s : pinnedStrSet) pinnedIds.add(Integer.parseInt(s));

            if (isPinned) {
                pinnedIds.remove(selectedApp.app.getAppId());
            } else {
                pinnedIds.add(selectedApp.app.getAppId());
            }

            // Save back as string set
            HashSet<String> pinnedStrings = new HashSet<>();
            for (Integer id : pinnedIds) pinnedStrings.add(id.toString());
            getSharedPreferences("nova_prefs", MODE_PRIVATE).edit()
                    .putStringSet("pinned_" + uuidString, pinnedStrings).apply();

            appGridAdapter.updatePinnedApps(pinnedIds);
        });

        addSheetAction(actions, getString(R.string.applist_menu_details), () -> {
            sheet.dismiss();
            Dialog.displayDialog(this, getString(R.string.title_details), selectedApp.app.toString(), false);
        });

        addSheetAction(actions, getString(R.string.applist_menu_export_launcher), () -> {
            sheet.dismiss();
            shortcutHelper.exportLauncherFile(computer, selectedApp.app);
        });

        sheet.show();
    }

    private void addSheetAction(LinearLayout container, String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(15);
        item.setTextColor(getColor(R.color.nova_text_primary));
        item.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        int pad = (int) UiHelper.dpToPx(this, 24);
        int padV = (int) UiHelper.dpToPx(this, 14);
        item.setPadding(pad, padV, pad, padV);
        item.setBackground(getDrawable(android.R.attr.selectableItemBackground));

        // Use selectableItemBackground properly
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        item.setBackgroundResource(outValue.resourceId);

        item.setOnClickListener(v -> action.run());
        container.addView(item);
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(View gridView) {
        if (gridView instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) gridView;
            int spanCount = Math.max(1, getResources().getDisplayMetrics().widthPixels / (int)(170 * getResources().getDisplayMetrics().density));
            rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, spanCount));
            rv.setAdapter(appGridAdapter);
            appGridAdapter.setOnItemClickListener(app -> {
                if (lastRunningAppId != 0) {
                    if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
                    } else {
                        showAppBottomSheet(app, appGridAdapter.itemList.indexOf(app));
                    }
                } else {
                    if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                        UiHelper.displayVdisplayConfirmationDialog(
                                AppView.this,
                                computer,
                                () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                                null
                        );
                    } else {
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
                    }
                }
            });
            rv.addOnItemTouchListener(new com.papi.nova.grid.RecyclerItemClickListener(this, rv, new com.papi.nova.grid.RecyclerItemClickListener.OnItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {}
                
                @Override
                public void onLongItemClick(View view, int position) {
                    AppObject app = (AppObject) appGridAdapter.getItem(position);
                    showAppBottomSheet(app, position);
                }
            }));
            UiHelper.applyStatusBarPadding(rv);
            rv.requestFocus();
        }
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;
        public boolean isPinned;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
