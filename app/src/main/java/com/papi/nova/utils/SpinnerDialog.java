package com.papi.nova.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.papi.nova.R;

public class SpinnerDialog implements Runnable, OnCancelListener {
    private final String title;
    private final String message;
    private final Activity activity;
    private AlertDialog dialog;
    private TextView messageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.dialog = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog d = i.next();
                if (d.activity == activity) {
                    i.remove();
                    if (d.dialog != null && d.dialog.isShowing()) {
                        d.dialog.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(() -> {
            if (messageView != null) {
                messageView.setText(message);
            }
        });
    }

    @Override
    public void run() {
        if (activity.isFinishing()) {
            return;
        }

        if (dialog == null)
        {
            // Build a Material-style dialog with inline progress indicator
            View content = LayoutInflater.from(activity).inflate(R.layout.nova_spinner_dialog, null);
            messageView = content.findViewById(R.id.spinner_message);
            messageView.setText(message);

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(title);
            builder.setView(content);
            builder.setOnCancelListener(this);

            if (finish) {
                builder.setCancelable(true);
            } else {
                builder.setCancelable(false);
            }

            dialog = builder.create();

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                dialog.show();
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface d) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }
        activity.finish();
    }
}
