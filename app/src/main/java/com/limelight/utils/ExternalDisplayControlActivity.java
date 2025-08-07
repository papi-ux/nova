package com.limelight.utils;

import static com.limelight.StartExternalDisplayControlReceiver.requestFocusToGameActivity;
import static com.limelight.utils.ServerHelper.getSecondaryDisplay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.Game;
import com.limelight.GameMenu;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.StartExternalDisplayControlReceiver;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * A standalone Activity providing a full-screen touchpad controller for the secondary display.
 * It creates its own UI programmatically and hosts the GameMenu for in-game options.
 */
public class ExternalDisplayControlActivity extends AppCompatActivity {

    public static String EXTRA_LAUNCH_INTENT = "launchIntent";

    @SuppressLint("StaticFieldLeak")
    public static ExternalDisplayControlActivity instance;

    private FrameLayout rootLayout;

    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable dimScreenRunnable;
    private float originalBrightness = -1f; // -1 = use system default
    private static final int INACTIVITY_TIMEOUT_MS = 10_000;


    private static final String NOTIFICATION_CHANNEL_ID = "secondary_screen_active_channel_id";
    public static final int SECONDARY_SCREEN_NOTIFICATION_ID = 1;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private NvConnection conn;
    private GameMenu gameMenu;
    private EditText dummyEditText;
    private boolean mIsEditingText = false;

    // --- Static Methods for External Control ---

    public static void closeExternalDisplayControl() {
        if (instance != null) {
            instance.finish();
        }
    }

    public static void toggleKeyboardForExternal() {
        if (instance != null) {
            instance.toggleKeyboard();
        }
    }

    public static void toggleGameMenu() {
        if (instance != null) {
            instance.showGameMenu();
        }
    }

    public static KeyBoardLayoutController getPhoneScreenKeyboard(PreferenceConfiguration prefConfig) {
        return new KeyBoardLayoutController(instance.rootLayout, instance, prefConfig);
    }

    // --- Activity Lifecycle ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        instance = this;

        if (!isGameInstanceAvailable()) {
            Intent gameIntent = getIntent().getParcelableExtra(EXTRA_LAUNCH_INTENT);
            if (gameIntent == null) {
                finish();
            } else {
                Display secondaryDisplay = getSecondaryDisplay(this);
                if (secondaryDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(secondaryDisplay.getDisplayId());
                    Toast.makeText(this,
                            getString(R.string.external_display_info) + " "
                                    + secondaryDisplay.getMode().getPhysicalWidth() + "x"
                                    + secondaryDisplay.getMode().getPhysicalHeight() + " "
                                    + secondaryDisplay.getMode().getRefreshRate() + "Hz",
                            Toast.LENGTH_LONG).show();

                    startActivity(gameIntent, options.toBundle());

                    // Wait for the intent to get started
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(this::initViews, 500);
                } else {
                    LimeLog.warning("NO EXTERNAL DISPLAY!!!???");
                    startActivity(gameIntent);
                    finish();
                }
            }
        } else {
            initViews();
        }
    }

    private void initViews() {
        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars());

        initializeComponents();
        createProgrammaticUI();
        checkNotificationPermission();
        initTouchEventHandling();
        setupKeyboardInputHandling();
        setupInactivityTimeoutForBrightness();
        requestFocusToGameActivity();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchEventHandling() {
        // Intercept touch events on root layout
        rootLayout.setOnTouchListener((v, event) -> {
            handleUserActivity();
            if (Game.instance != null) {
                Game.instance.handleMotionEvent(v, event);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isGameInstanceAvailable() && gameMenu != null) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isGameInstanceAvailable()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInactivityTimeoutForBrightness() {
        // Save the original brightness
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        originalBrightness = layout.screenBrightness;

        // Runnable to dim screen
        dimScreenRunnable = () -> {
            WindowManager.LayoutParams l = getWindow().getAttributes();
            l.screenBrightness = 0.0f;
            getWindow().setAttributes(l);
        };

        // Start the timer
        resetInactivityTimer();
    }

    private void handleUserActivity() {
        // Restore brightness if dimmed
        WindowManager.LayoutParams l = getWindow().getAttributes();
        if (l.screenBrightness == 0.0f) {
            l.screenBrightness = originalBrightness;
            getWindow().setAttributes(l);
        }

        resetInactivityTimer();
    }

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(dimScreenRunnable);
        inactivityHandler.postDelayed(dimScreenRunnable, INACTIVITY_TIMEOUT_MS);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isGameInstanceAvailable()) {
            Game.instance.handleFocusChange(hasFocus);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if(Game.instance != null && Game.instance.isKeyboardLayoutVisible()) {
            togglePcKeyboard();
        } else if(gameMenu != null && !gameMenu.isMenuOpen() && Game.instance != null)
            Game.instance.onBackPressed();
        else {
            super.onBackPressed();
        }
    }

    // --- Initialization and UI Creation ---

    /**
     * Checks if the static Game.instance is alive. If not, finishes this Activity.
     */
    private boolean isGameInstanceAvailable() {
        return Game.instance != null;
    }

    /**
     * Initializes core components needed for this controller Activity.
     */
    private void initializeComponents() {
        this.gameMenu = new GameMenu(Game.instance, instance);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if(Game.instance != null) {
            Game.instance.onConfigurationChanged(newConfig);
        }
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (Game.instance != null) {
            requestFocusToGameActivity();
            return Game.instance.onGenericMotionEvent(event);
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createProgrammaticUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.setFocusedByDefault(true);
        }
        setContentView(rootLayout);

        // Top-right buttons
        LinearLayout topRightButtons = createButtonContainer(Gravity.TOP | Gravity.END);
        rootLayout.addView(topRightButtons);
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external, v -> showGameMenu()));
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external, v -> finish()));
        topRightButtons.setFocusable(false);

        // Top-left buttons
        LinearLayout topLeftButtons = createButtonContainer(Gravity.TOP | Gravity.START);
        rootLayout.addView(topLeftButtons);
        topLeftButtons.addView(createImageButton(R.drawable.ic_focus_secondary, v -> requestFocusToGameActivity()));
        ImageButton zoomButton = createImageButton(R.drawable.ic_zoom_toggle, v -> {
            if (Game.instance != null) {
                toggleZoomMode();
                if (Game.instance.isZoomModeEnabled()) {
                    ((ImageButton)v).setAlpha(1.0f);
                } else {
                    ((ImageButton)v).setAlpha(0.5f);
                }
            }
        });
        if (Game.instance != null && Game.instance.isZoomModeEnabled()) {
            zoomButton.setAlpha(1.0f);
        } else {
            zoomButton.setAlpha(0.5f);
        }
        topLeftButtons.addView(zoomButton);
        topLeftButtons.setFocusable(false);

        // Bottom-center buttons
        LinearLayout bottomCenterButtons = createButtonContainer(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rootLayout.addView(bottomCenterButtons);
        bottomCenterButtons.setFocusable(false);

        // Bottom-left button: Android keyboard toggle
        LinearLayout bottomLeftButton = createButtonContainer(Gravity.BOTTOM | Gravity.START);
        rootLayout.addView(bottomLeftButton);
        bottomLeftButton.addView(createImageButton(R.drawable.ic_android_keyboard, v -> toggleKeyboard()));
        bottomLeftButton.setFocusable(false);

        // Bottom-right button: Custom keyboard toggle
        LinearLayout bottomRightButton = createButtonContainer(Gravity.BOTTOM | Gravity.END);
        rootLayout.addView(bottomRightButton);
        bottomRightButton.addView(createImageButton(R.drawable.ic_fullscreen_keyboard, v -> togglePcKeyboard()));
        bottomRightButton.setFocusable(false);
    }


    /**
     * Sets up the hidden EditText and its listeners to handle soft keyboard input.
     */
    private void setupKeyboardInputHandling() {
        dummyEditText = new EditText(this);
        dummyEditText.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
        dummyEditText.setAlpha(0f);
        dummyEditText.setFocusableInTouchMode(true);
        rootLayout.addView(dummyEditText);

        // Listener for hardware keys (if any) sent to this view
        dummyEditText.setOnKeyListener((view, i,keyEvent) -> {
            if (Game.instance != null) {
                return Game.instance.onKey(view, i, keyEvent);
            }
            return false;
        });

        // Set and handle the "Enter" key's special action (e.g., "Done", "Go")
        dummyEditText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        dummyEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (Game.instance != null) {
                    hitEnter();
                    toggleKeyboard();
                }
                return true;
            }
            return false;
        });

        // Listener for regular typed characters and pasted text
        dummyEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mIsEditingText) return;

                if (s.length() > 0 && Game.instance != null) {
                    mIsEditingText = true;
                    // Enter without closing keyboard
                    if (s.charAt(s.length() - 1) == '\n') {
                        hitEnter();
                    } else {
                        Game.instance.conn.sendUtf8Text(s.toString());
                    }
                    s.clear();
                    mIsEditingText = false;
                }
            }
        });
    }

    /**
     * Toggles the visibility of the on-screen software keyboard.
     */
    private void toggleKeyboard() {
        dummyEditText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
        }
    }


    /**
     * Toggles the visibility of the full screen keyboard
     */
    private void togglePcKeyboard() {
        if(Game.instance != null) {
            Game.instance.showHidekeyBoardLayoutController();
        }
    }

    private void toggleZoomMode() {
        if(Game.instance != null) {
            Game.instance.toggleZoomMode();
        }
    }

    // --- Public methods to interact with the GameMenu instance ---

    public void showGameMenu() {
        if (gameMenu != null) {
            gameMenu.showMenu(null);
        }
    }

    private void hitEnter() {
        Game.instance.onKey(null, KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        Game.instance.onKey(null, KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    // --- UI Factory Methods ---

    private LinearLayout createButtonContainer(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(gravity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        layout.setLayoutParams(params);
        return layout;
    }

    private ImageButton createImageButton(int imageResourceId, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(imageResourceId);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        return button;
    }

    // --- Notification Management ---

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        showStickyNotification();
    }

    @SuppressLint("NotificationTrampoline")
    private void showStickyNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Second Screen Control", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent broadcastIntent = new Intent(this, StartExternalDisplayControlReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Bitmap logoBitmap = drawableToBitmap(new ArtemisLogoDrawable());

        // 2. Create an IconCompat object from the Bitmap. This is the support library's
        //    way of handling icons for maximum compatibility.
        IconCompat icon = IconCompat.createWithBitmap(logoBitmap);
        @SuppressLint("NotificationTrampoline") Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Second Screen Active")
                    .setContentText("Tap to open touchpad controller.")
                    .setSmallIcon(icon)
                    .setLargeIcon(logoBitmap)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Second Screen Active")
                    .setContentText("Tap to open touchpad controller.")
                    .setLargeIcon(logoBitmap)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }
        notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification);
    }

    /**
     * Helper method to convert any Drawable into a Bitmap.
     * This is necessary to use a programmatic drawable as a notification icon.
     */
    private Bitmap drawableToBitmap(Drawable drawable) {
        // A notification icon is typically 24x24 dp. We'll create a 96x96 px bitmap
        // which will scale down nicely on most densities.
        int width = dpToPx(24);
        int height = dpToPx(24);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showStickyNotification();
            } else {
                Toast.makeText(this, "Notification permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Utility Methods ---

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}