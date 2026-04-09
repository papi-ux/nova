package com.papi.nova.grid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.papi.nova.AppView;
import com.papi.nova.LimeLog;
import com.papi.nova.R;
import com.papi.nova.grid.assets.CachedAppAssetLoader;
import com.papi.nova.grid.assets.DiskAssetLoader;
import com.papi.nova.grid.assets.MemoryAssetLoader;
import com.papi.nova.grid.assets.NetworkAssetLoader;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 110;
    private static final int LARGE_WIDTH_DP = 170;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private Set<Integer> pinnedAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    private String searchFilter = "";

    public void filterByName(String query) {
        this.searchFilter = query.toLowerCase().trim();
        rebuildFilteredList();
    }

    private void rebuildFilteredList() {
        itemList.clear();
        for (AppView.AppObject app : allApps) {
            app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            if (app.isHidden && !showHiddenApps) continue;
            if (!searchFilter.isEmpty() &&
                !app.app.getAppName().toLowerCase().contains(searchFilter)) continue;
            itemList.add(app);
        }
        sortList(itemList);
        notifyDataSetChanged();
    }

    public int getTotalAppCount() {
        return allApps.size();
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                createPlaceholderBitmap(context));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    private static Bitmap createPlaceholderBitmap(Context context) {
        // Vector drawables can't be decoded by BitmapFactory — render to bitmap manually
        Drawable d = ContextCompat.getDrawable(context, R.drawable.nova_app_placeholder);
        if (d == null) {
            // Final fallback: 1x1 transparent bitmap
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 200;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 266;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        return bitmap;
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                // Pinned games always sort first
                if (lhs.isPinned != rhs.isPinned) {
                    return lhs.isPinned ? -1 : 1;
                }
                int lIndex = lhs.app.getAppIndex();
                int rIndex = rhs.app.getAppIndex();
                if (lIndex == rIndex) {
                    return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
                } else {
                    return lIndex - rIndex;
                }
            }
        });
    }

    public void updatePinnedApps(Set<Integer> newPinnedIds) {
        this.pinnedAppIds.clear();
        this.pinnedAppIds.addAll(newPinnedIds);
        for (AppView.AppObject app : allApps) {
            app.isPinned = pinnedAppIds.contains(app.app.getAppId());
        }
        rebuildFilteredList();
    }

    public boolean isAppPinned(int appId) {
        return pinnedAppIds.contains(appId);
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden and pinned state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());
        app.isPinned = pinnedAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        // Running state indicators
        View runningBadge = parentView.findViewById(R.id.running_badge);
        View runningBorder = parentView.findViewById(R.id.running_border);

        if (obj.isRunning) {
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
            gridMask.setBackgroundColor(0x44000000);
            if (runningBadge != null) runningBadge.setVisibility(View.VISIBLE);
            if (runningBorder != null) runningBorder.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
            gridMask.setBackgroundColor(0x00000000);
            if (runningBadge != null) runningBadge.setVisibility(View.GONE);
            if (runningBorder != null) runningBorder.setVisibility(View.GONE);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
