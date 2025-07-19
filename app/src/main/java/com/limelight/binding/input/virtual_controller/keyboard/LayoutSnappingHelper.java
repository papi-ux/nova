package com.limelight.binding.input.virtual_controller.keyboard;

import android.view.View;
import android.widget.FrameLayout;

public class LayoutSnappingHelper {
    private static final int SNAP_THRESHOLD = 10; // Pixels threshold for snapping
    private static final float OVERLAP_THRESHOLD = 0.5f; // 50% overlap required to trigger size matching
    private static final int SPACING_MIN = 4; // Minimum spacing between parallel edges
    private static final int SPACING_THRESHOLD = 30; // Maximum distance to trigger spacing adjustment

    public static class SnapResult {
        public int newX;
        public int newY;
        public int newWidth;
        public int newHeight;
        public boolean didSnap;
        public boolean didResize;
        public boolean didAdjustSpacing;

        public SnapResult(int x, int y, int width, int height, boolean snapped, boolean resized, boolean adjustedSpacing) {
            this.newX = x;
            this.newY = y;
            this.newWidth = width;
            this.newHeight = height;
            this.didSnap = snapped;
            this.didResize = resized;
            this.didAdjustSpacing = adjustedSpacing;
        }
    }

    private static boolean isOverlapping(View view1, int x1, int y1, View view2, int x2, int y2) {
        int right1 = x1 + view1.getWidth();
        int bottom1 = y1 + view1.getHeight();
        int right2 = x2 + view2.getWidth();
        int bottom2 = y2 + view2.getHeight();

        // Calculate overlap area
        int overlapX = Math.min(right1, right2) - Math.max(x1, x2);
        int overlapY = Math.min(bottom1, bottom2) - Math.max(y1, y2);

        if (overlapX <= 0 || overlapY <= 0) return false;

        // Calculate overlap percentage
        float overlapArea = overlapX * overlapY;
        float view1Area = view1.getWidth() * view1.getHeight();
        float view2Area = view2.getWidth() * view2.getHeight();

        float overlapPercentage;
        if (view1Area > view2Area) {
            overlapPercentage = overlapArea / view2Area;
        } else {
            overlapPercentage = overlapArea / view1Area;
        }

        return overlapPercentage >= OVERLAP_THRESHOLD;
    }

    private static boolean hasParallelEdges(int edge1Start, int edge1End, int edge2Start, int edge2End) {
        // Check if edges have significant overlap
        int overlapStart = Math.max(edge1Start, edge2Start);
        int overlapEnd = Math.min(edge1End, edge2End);
        return overlapEnd - overlapStart > Math.min(edge1End - edge1Start, edge2End - edge2Start) * 0.5;
    }

    private static int adjustSpacing(int distance) {
        if (distance > SPACING_MIN && distance < SPACING_THRESHOLD) {
            return SPACING_MIN;
        }
        return distance;
    }

    public static SnapResult calculateSnappedPosition(View movingView, View[] otherViews, int proposedX, int proposedY) {
        int snappedX = proposedX;
        int snappedY = proposedY;
        int newWidth = ((FrameLayout.LayoutParams) movingView.getLayoutParams()).width;
        int newHeight = ((FrameLayout.LayoutParams) movingView.getLayoutParams()).height;
        boolean didSnap = false;
        boolean didResize = false;
        boolean didAdjustSpacing = false;

        FrameLayout.LayoutParams movingParams = (FrameLayout.LayoutParams) movingView.getLayoutParams();
        int movingWidth = movingParams.width;
        int movingHeight = movingParams.height;

        for (View otherView : otherViews) {
            if (otherView == movingView || otherView.getVisibility() != View.VISIBLE) {
                continue;
            }

            FrameLayout.LayoutParams otherParams = (FrameLayout.LayoutParams) otherView.getLayoutParams();
            
            // Check for overlap and resize if needed
            if (isOverlapping(movingView, proposedX, proposedY, otherView, otherParams.leftMargin, otherParams.topMargin)) {
                newWidth = otherView.getWidth();
                newHeight = otherView.getHeight();
                didResize = true;
            }

            // Check vertical parallel edges
            if (hasParallelEdges(proposedY, proposedY + movingHeight, 
                               otherParams.topMargin, otherParams.topMargin + otherView.getHeight())) {
                // Left edges
                int leftDistance = Math.abs(proposedX - (otherParams.leftMargin + otherView.getWidth()));
                if (leftDistance > SPACING_MIN && leftDistance < SPACING_THRESHOLD) {
                    snappedX = otherParams.leftMargin + otherView.getWidth() + SPACING_MIN;
                    didAdjustSpacing = true;
                }
                
                // Right edges
                int rightDistance = Math.abs((proposedX + movingWidth) - otherParams.leftMargin);
                if (rightDistance > SPACING_MIN && rightDistance < SPACING_THRESHOLD) {
                    snappedX = otherParams.leftMargin - SPACING_MIN - movingWidth;
                    didAdjustSpacing = true;
                }
            }

            // Check horizontal parallel edges
            if (hasParallelEdges(proposedX, proposedX + movingWidth,
                               otherParams.leftMargin, otherParams.leftMargin + otherView.getWidth())) {
                // Top edges
                int topDistance = Math.abs(proposedY - (otherParams.topMargin + otherView.getHeight()));
                if (topDistance > SPACING_MIN && topDistance < SPACING_THRESHOLD) {
                    snappedY = otherParams.topMargin + otherView.getHeight() + SPACING_MIN;
                    didAdjustSpacing = true;
                }
                
                // Bottom edges
                int bottomDistance = Math.abs((proposedY + movingHeight) - otherParams.topMargin);
                if (bottomDistance > SPACING_MIN && bottomDistance < SPACING_THRESHOLD) {
                    snappedY = otherParams.topMargin - SPACING_MIN - movingHeight;
                    didAdjustSpacing = true;
                }
            }

            // Normal snapping checks
            if (Math.abs(proposedX - otherParams.leftMargin) < SNAP_THRESHOLD) {
                snappedX = otherParams.leftMargin;
                didSnap = true;
            }
            if (Math.abs((proposedX + movingWidth) - (otherParams.leftMargin + otherView.getWidth())) < SNAP_THRESHOLD) {
                snappedX = otherParams.leftMargin + otherView.getWidth() - movingWidth;
                didSnap = true;
            }
            if (Math.abs(proposedY - otherParams.topMargin) < SNAP_THRESHOLD) {
                snappedY = otherParams.topMargin;
                didSnap = true;
            }
            if (Math.abs((proposedY + movingHeight) - (otherParams.topMargin + otherView.getHeight())) < SNAP_THRESHOLD) {
                snappedY = otherParams.topMargin + otherView.getHeight() - movingHeight;
                didSnap = true;
            }
        }

        return new SnapResult(snappedX, snappedY, newWidth, newHeight, didSnap, didResize, didAdjustSpacing);
    }
} 
