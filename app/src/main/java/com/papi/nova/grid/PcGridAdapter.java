package com.papi.nova.grid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.papi.nova.PcViewModel;
import com.papi.nova.R;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.ui.NovaThemeManager;

import java.util.Collections;
import java.util.Comparator;

import androidx.core.content.ContextCompat;

public class PcGridAdapter extends GenericGridAdapter<PcViewModel.ComputerObject> {

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcViewModel.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcViewModel.ComputerObject>() {
            @Override
            public int compare(PcViewModel.ComputerObject lhs, PcViewModel.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcViewModel.ComputerObject computer) {
        return itemList.remove(computer);
    }

    /** Cached references for PcView-specific views (status dot + text). */
    private static class PcViewHolder {
        View statusDot;
        TextView statusText;
        TextView primaryAction;
    }

    private static final int TAG_PC_HOLDER = R.id.status_dot; // reuse an existing ID as tag key

    private PcViewHolder getPcHolder(View parentView) {
        Object tag = parentView.getTag(TAG_PC_HOLDER);
        if (tag instanceof PcViewHolder) {
            return (PcViewHolder) tag;
        }
        PcViewHolder holder = new PcViewHolder();
        holder.statusDot = parentView.findViewById(R.id.status_dot);
        holder.statusText = parentView.findViewById(R.id.status_text);
        holder.primaryAction = parentView.findViewById(R.id.primary_action_text);
        parentView.setTag(TAG_PC_HOLDER, holder);
        return holder;
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, PcViewModel.ComputerObject obj) {
        applyCardTheme(parentView, imgView, prgView, txtView);

        imgView.setImageResource(R.drawable.ic_computer);
        imgView.setColorFilter(NovaThemeManager.INSTANCE.getTextSecondaryColor(context));

        // Status indicator (cached via PcViewHolder)
        PcViewHolder pcHolder = getPcHolder(parentView);
        View statusDot = pcHolder.statusDot;
        TextView statusText = pcHolder.statusText;
        TextView primaryAction = pcHolder.primaryAction;

        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
            if (statusDot != null) {
                statusDot.setBackgroundResource(R.drawable.nova_status_online);
            }
            if (statusText != null) {
                if (obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
                    statusText.setText("Online \u00b7 Not Paired");
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning));
                    if (primaryAction != null) {
                        primaryAction.setText(R.string.pcview_card_action_pair);
                    }
                } else if (obj.details.runningGameId != 0) {
                    statusText.setText("Streaming");
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_success));
                    if (primaryAction != null) {
                        primaryAction.setText(R.string.pcview_card_action_resume);
                    }
                } else {
                    String addr = obj.details.activeAddress != null ? obj.details.activeAddress.address : "";
                    statusText.setText("Ready \u00b7 " + addr);
                    statusText.setTextColor(NovaThemeManager.INSTANCE.getTextMutedColor(context));
                    if (primaryAction != null) {
                        primaryAction.setText(R.string.pcview_card_action_open_apps);
                    }
                }
            }
        }
        else if (obj.details.state == ComputerDetails.State.OFFLINE) {
            imgView.setAlpha(0.4f);
            if (statusDot != null) {
                statusDot.setBackgroundResource(R.drawable.nova_status_offline);
            }
            if (statusText != null) {
                statusText.setText("Offline");
                statusText.setTextColor(NovaThemeManager.INSTANCE.getTextMutedColor(context));
            }
            if (primaryAction != null) {
                primaryAction.setText(R.string.pcview_card_action_wake);
            }
        }
        else {
            imgView.setAlpha(0.6f);
            if (statusDot != null) {
                statusDot.setBackgroundResource(R.drawable.nova_status_connecting);
            }
            if (statusText != null) {
                statusText.setText("Connecting\u2026");
                statusText.setTextColor(NovaThemeManager.INSTANCE.getTextMutedColor(context));
            }
            if (primaryAction != null) {
                primaryAction.setText(R.string.pcview_card_action_refreshing);
            }
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        txtView.setText(obj.details.name);
        txtView.setAlpha(obj.details.state == ComputerDetails.State.ONLINE ? 1.0f : 0.6f);

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.4f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }
    }

    private void applyCardTheme(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView) {
        View card = parentView instanceof android.view.ViewGroup && ((android.view.ViewGroup) parentView).getChildCount() > 0
                ? ((android.view.ViewGroup) parentView).getChildAt(0)
                : parentView;

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(context.getResources().getDisplayMetrics().density * 16f);
        background.setColor(NovaThemeManager.INSTANCE.getCardBackgroundColor(context));
        background.setStroke((int) (context.getResources().getDisplayMetrics().density), NovaThemeManager.INSTANCE.getDividerColor(context));
        card.setBackground(background);

        txtView.setTextColor(NovaThemeManager.INSTANCE.getTextPrimaryColor(context));
        prgView.setIndeterminateTintList(ColorStateList.valueOf(NovaThemeManager.INSTANCE.getAccentColor(context)));
        imgView.setImageTintList(ColorStateList.valueOf(NovaThemeManager.INSTANCE.getTextSecondaryColor(context)));

        TextView primaryAction = parentView.findViewById(R.id.primary_action_text);
        if (primaryAction != null) {
            primaryAction.setTextColor(NovaThemeManager.INSTANCE.getAccentColor(context));
        }
    }
}
