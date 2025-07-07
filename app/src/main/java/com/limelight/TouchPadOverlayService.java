package com.limelight;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.view.MotionEvent.INVALID_POINTER_ID;
import static com.limelight.Game.FIVE_FINGER_TAP_THRESHOLD;
import static com.limelight.Game.FOUR_FINGER_TAP_THRESHOLD;
import static com.limelight.Game.THREE_FINGER_TAP_THRESHOLD;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class TouchPadOverlayService extends Service {
    private WindowManager windowManager;
    private FrameLayout touchpadView;

    private int pointerId1 = INVALID_POINTER_ID;
    private int pointerId2 = INVALID_POINTER_ID;

    private float lastX1, lastY1, lastX2, lastY2;

    private PreferenceConfiguration prefConfig;

    private final TouchContext[] trackpadContextMap = new TouchContext[2];
    private KeyBoardLayoutController keyBoardLayoutController;

    private long synthTouchDownTime = 0;

    private boolean pendingDrag = false;
    private boolean isDragging = false;
    private float lastTouchDownX, lastTouchDownY;

    private final long threeFingerDownTime = 0;
    private final long fourFingerDownTime = 0;
    private final long fiveFingerDownTime = 0;
    private boolean synthClickPending = false;
    private boolean pointerSwiping = false;

    private float lastX = -1;
    private float lastY = -1;

    private NvConnection conn;

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        Intent intent = new Intent(this, SecondaryScreenNotification.class);
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display mainDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.setLaunchDisplayId(mainDisplay.getDisplayId());
        }
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent, options.toBundle());

        prefConfig = PreferenceConfiguration.readPreferences(this);

        touchpadView = new FrameLayout(this);
        touchpadView.setBackgroundColor(Color.BLACK);

        touchpadView.setClickable(true);
        touchpadView.setFocusable(true);

        // Initialize trackpad contexts
        for (int i = 0; i < trackpadContextMap.length; i++) {
            trackpadContextMap[i] = new TrackpadContext(conn, i, prefConfig.trackpadSwapAxis, prefConfig.trackpadSensitivityX, prefConfig.trackpadSensitivityY);
        }

        // Create the close button
        ImageButton closeButton = new ImageButton(this);
        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel); // simple X icon
        closeButton.setBackgroundColor(Color.TRANSPARENT); // no background
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonParams.gravity = Gravity.TOP | Gravity.END; // top-right corner
        buttonParams.setMargins(16, 16, 16, 16); // optional margin

        closeButton.setLayoutParams(buttonParams);


        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeTouchpad();
            }
        });

        if (Game.instance != null) {
            conn = Game.instance.conn;
        }

        touchpadView.setOnTouchListener((v, event) -> {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                // Handle trackpad when pointer is not captured by synthesizing a trackpad movement
                int eventAction = event.getActionMasked();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
                    if (!pointerSwiping) {
                        pointerSwiping = true;
                        handleTouchInput(event, trackpadContextMap, false, MotionEvent.ACTION_POINTER_DOWN, 1, 2);
                    }
                    return handleTouchInput(event, trackpadContextMap, false, MotionEvent.ACTION_MOVE, 1, 2);
                } else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP) {
                    pointerSwiping = false;
                    synthClickPending = false;
                    handleTouchInput(event, trackpadContextMap, false, MotionEvent.ACTION_POINTER_UP, 1, 2);
                    return true;
                }

                // Press & Hold / Double-Tap & Hold for Selection or Drag & Drop
                double positionDelta = Math.sqrt(
                        Math.pow(event.getX() - lastTouchDownX, 2) +
                                Math.pow(event.getY() - lastTouchDownY, 2)
                );

                if (synthClickPending &&
                        event.getEventTime() - synthTouchDownTime >= prefConfig.trackpadDragDropThreshold) {
                    if (positionDelta > 50) {
                        pendingDrag = false;
                    } else if (pendingDrag) {
                        pendingDrag = false;
                        isDragging = true;
                        if (prefConfig.trackpadDragDropVibration) {
                            Vibrator vibrator = ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE));
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(20, 127));
                            } else {
                                vibrator.vibrate(20);
                            }
                        }
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        return true;
                    }
                }
                long timeDiff = event.getEventTime() - synthTouchDownTime;
                switch (eventAction) {
                    case MotionEvent.ACTION_HOVER_MOVE:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (event.getPointerCount() == 2) {
                            // Save pointer IDs of the two fingers
                            pointerId1 = event.getPointerId(0);
                            pointerId2 = event.getPointerId(1);

                            lastX1 = event.getX(event.findPointerIndex(pointerId1));
                            lastY1 = event.getY(event.findPointerIndex(pointerId1));
                            lastX2 = event.getX(event.findPointerIndex(pointerId2));
                            lastY2 = event.getY(event.findPointerIndex(pointerId2));
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (pointerId1 != INVALID_POINTER_ID && pointerId2 != INVALID_POINTER_ID && event.getPointerCount() >= 2) {
                            // Get current positions
                            float x1 = event.getX(event.findPointerIndex(pointerId1));
                            float y1 = event.getY(event.findPointerIndex(pointerId1));
                            float x2 = event.getX(event.findPointerIndex(pointerId2));
                            float y2 = event.getY(event.findPointerIndex(pointerId2));

                            // Calculate average movement delta (two-finger swipe distance)
                            float deltaX = ((x1 - lastX1) + (x2 - lastX2)) / 2;
                            float deltaY = ((y1 - lastY1) + (y2 - lastY2)) / 2;

                            lastX1 = x1;
                            lastY1 = y1;
                            lastX2 = x2;
                            lastY2 = y2;

                            conn.sendMouseHighResScroll(((short) (deltaY)));
                            conn.sendMouseHighResHScroll(((short) (deltaX)));
                        } else {
                            float x = event.getX();
                            float y = event.getY();

                            if (lastX >= 0 && lastY >= 0) {
                                float dx = x - lastX;
                                float dy = y - lastY;

                                if (isConnected())
                                    conn.sendMouseMove((short) dx, (short) dy);
                            }

                            lastX = x;
                            lastY = y;
                        }
                        return true;
                    case MotionEvent.ACTION_HOVER_EXIT:
                    case MotionEvent.ACTION_DOWN:
                        pendingDrag = true;
                        synthClickPending = true;
                        lastTouchDownX = event.getX();
                        lastTouchDownY = event.getY();
                        synthTouchDownTime = event.getEventTime();
                        return true;
                    case MotionEvent.ACTION_HOVER_ENTER:
                    case MotionEvent.ACTION_UP:
                        lastX = -1;
                        lastY = -1;
                        if (synthClickPending) {
                            if (timeDiff < 120) {
                                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                            } else if (timeDiff < 300) {
                                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                            }

                            if (isDragging) {
                                isDragging = false;
                                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                            }
                            pendingDrag = false;
                            synthClickPending = false;
                        }
                        return true;
                    case MotionEvent.ACTION_BUTTON_PRESS:
                    case MotionEvent.ACTION_BUTTON_RELEASE:
                        synthClickPending = false;
                    default:
                        break;
                }
            }
            return true;
        });

        initkeyBoardLayoutController();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        touchpadView.addView(closeButton);
        windowManager.addView(touchpadView, params);
    }

    private void closeTouchpad() {
        stopSelf();
        // This will just resume the running second screen and move input focus to it
        if (conn != null) {
            Intent gameIntent = new Intent(this, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gameIntent);
        }
    }

    private TouchContext getTouchContext(int actionIndex, TouchContext[] inputContextMap) {
        if (actionIndex < inputContextMap.length) {
            return inputContextMap[actionIndex];
        } else {
            return null;
        }
    }

    private float[] getNormalizedCoordinates(View streamView, float rawX, float rawY) {
        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();

        float normalizedX = (rawX - streamView.getX()) / scaleX;
        float normalizedY = (rawY - streamView.getY()) / scaleY;

        return new float[]{normalizedX, normalizedY};
    }

    private boolean handleTouchInput(MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen, int eventAction, int actionIndex, int pointerCount) {
        int actualActionIndex = event.getActionIndex();
        int actualPointerCount = event.getPointerCount();

        boolean shouldDuplicateMovement = actualPointerCount < pointerCount;

        int eventX = (int) event.getX(actualActionIndex);
        int eventY = (int) event.getY(actualActionIndex);

        // Handle view scaling
        if (isTouchScreen) {
            float[] normalizedCoords = getNormalizedCoordinates(touchpadView, eventX, eventY);
            eventX = (int) normalizedCoords[0];
            eventY = (int) normalizedCoords[1];
        }

        TouchContext context = getTouchContext(actionIndex, inputContextMap);
        if (context == null) {
            return false;
        }

        switch (eventAction) {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount);
                }
                context.touchDownEvent(eventX, eventY, event.getEventTime(), true);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                //是触控板模式 三点呼出软键盘
                if (prefConfig.touchscreenTrackpad) {
                    if (pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up
                        long currentEventTime = event.getEventTime();
                        if (currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            showHidekeyBoardLayoutController();
                            return true;
                        } else if (currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                            showHidekeyBoardLayoutController();
                            return true;
                        } else if (currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                            if (prefConfig.enableBackMenu) {
                                //     showGameMenu(null);
                            }
                            return true;
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    context.cancelTouch();
                } else {
                    context.touchUpEvent(eventX, eventY, event.getEventTime());
                }

                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount - 1);
                }
                if (actionIndex == 0 && pointerCount > 1 && !context.isCancelled()) {
                    // The original secondary touch now becomes primary
                    int pointer1X = (int) event.getX(1);
                    int pointer1Y = (int) event.getY(1);
                    if (isTouchScreen) {
                        float[] normalizedCoords = getNormalizedCoordinates(touchpadView, pointer1X, pointer1Y);
                        pointer1X = (int) normalizedCoords[0];
                        pointer1Y = (int) normalizedCoords[1];
                    }
                    context.touchDownEvent(
                            pointer1X,
                            pointer1Y,
                            event.getEventTime(), false);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                // ACTION_MOVE is special because it always has actionIndex == 0
                // We'll call the move handlers for all indexes manually

                // First process the historical events
                for (int i = 0; i < event.getHistorySize(); i++) {
                    for (TouchContext aTouchContextMap : inputContextMap) {
                        if (aTouchContextMap.getActionIndex() < pointerCount) {
                            int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                            int historicalX = (int) event.getHistoricalX(aActionIndex, i);
                            int historicalY = (int) event.getHistoricalY(aActionIndex, i);
                            if (isTouchScreen) {
                                float[] normalizedCoords = getNormalizedCoordinates(touchpadView, historicalX, historicalY);
                                historicalX = (int) normalizedCoords[0];
                                historicalY = (int) normalizedCoords[1];
                            }
                            aTouchContextMap.touchMoveEvent(
                                    historicalX,
                                    historicalY,
                                    event.getHistoricalEventTime(i));
                        }
                    }
                }

                // Now process the current values
                for (TouchContext aTouchContextMap : inputContextMap) {
                    if (aTouchContextMap.getActionIndex() < pointerCount) {
                        int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                        int currentX = (int) event.getX(aActionIndex);
                        int currentY = (int) event.getY(aActionIndex);
                        if (isTouchScreen) {
                            float[] normalizedCoords = getNormalizedCoordinates(touchpadView, currentX, currentY);
                            currentX = (int) normalizedCoords[0];
                            currentY = (int) normalizedCoords[1];
                        }
                        aTouchContextMap.touchMoveEvent(
                                currentX,
                                currentY,
                                event.getEventTime());
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (TouchContext aTouchContext : inputContextMap) {
                    aTouchContext.cancelTouch();
                    aTouchContext.setPointerCount(0);
                }
                break;
            default:
                return false;
        }

        return true;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (touchpadView != null) windowManager.removeView(touchpadView);
    }

    private Boolean isConnected() {
        return Game.instance != null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initkeyBoardLayoutController() {
        keyBoardLayoutController = new KeyBoardLayoutController(touchpadView, this, prefConfig);
        keyBoardLayoutController.refreshLayout();
        keyBoardLayoutController.show();
    }

    public void showHidekeyBoardLayoutController() {
        if (keyBoardLayoutController == null) {
            initkeyBoardLayoutController();
            return;
        }
        keyBoardLayoutController.toggleVisibility();
    }
}

