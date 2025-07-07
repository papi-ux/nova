package com.limelight;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class SecondaryScreenNotification extends AppCompatActivity {

    private static final String SECONDARY_SCREEN_ACTIVE_CHANNEL_ID = "secondary_screen_active_channel_id";
    public static final int SECONDARY_SCREEN_NOTIFICATION_ID = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkNotificationPermissionAndShow();
    }

    private void checkNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION
                );
                return;
            }
        }
        showStickyNotification();
        finish();
    }

    private void showStickyNotification() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    SECONDARY_SCREEN_ACTIVE_CHANNEL_ID,
                    "SecondScreen is active",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent broadcastIntent = new Intent(this, StartTouchPadReceiver.class);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, SECONDARY_SCREEN_ACTIVE_CHANNEL_ID)
                .setContentTitle("Second Screen is active")
                .setContentText("Touch to open virtual touchpad or make physical keyboard mouse events work again on second screen")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showStickyNotification();
            } else {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
