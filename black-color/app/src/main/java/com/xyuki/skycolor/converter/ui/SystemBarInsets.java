package com.xyuki.skycolor.converter.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Applies platform system-bar and IME insets without AndroidX dependencies. */
public final class SystemBarInsets {
    private static final int STATUS_BAR_COLOR = Color.rgb(83, 67, 177);
    private static final int NAVIGATION_BAR_COLOR = Color.rgb(248, 247, 255);

    private SystemBarInsets() {
    }

    public static void install(Activity activity, View root, View topBar, View bottomBar) {
        if (activity == null || root == null) {
            throw new IllegalArgumentException("系统栏宿主不能为空");
        }
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(STATUS_BAR_COLOR);
        window.setNavigationBarColor(NAVIGATION_BAR_COLOR);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(NAVIGATION_BAR_COLOR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        int systemUiVisibility = window.getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before API 30 there is no setDecorFitsSystemWindows(). Keep the content behind
            // both bars and apply the legacy insets below so the two screens use one layout rule.
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 may make the status bar transparent for targetSdk 35. Both screens
            // draw a light surface behind it, so use dark icons when the platform ignores the
            // requested purple status-bar color.
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            systemUiVisibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);

        int topBaseLeft = topBar == null ? 0 : topBar.getPaddingLeft();
        int topBaseTop = topBar == null ? 0 : topBar.getPaddingTop();
        int topBaseRight = topBar == null ? 0 : topBar.getPaddingRight();
        int topBaseBottom = topBar == null ? 0 : topBar.getPaddingBottom();
        int bottomBaseLeft = bottomBar == null ? 0 : bottomBar.getPaddingLeft();
        int bottomBaseTop = bottomBar == null ? 0 : bottomBar.getPaddingTop();
        int bottomBaseRight = bottomBar == null ? 0 : bottomBar.getPaddingRight();
        int bottomBaseBottom = bottomBar == null ? 0 : bottomBar.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets status = insets.getInsets(WindowInsets.Type.statusBars());
                android.graphics.Insets navigation = insets.getInsets(WindowInsets.Type.navigationBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                android.graphics.Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
                topInset = status.top;
                bottomInset = Math.max(Math.max(navigation.bottom, ime.bottom), gestures.bottom);
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bottomInset = Math.max(bottomInset, insets.getSystemGestureInsets().bottom);
                }
            }
            if (topBar != null) {
                topBar.setPadding(
                        topBaseLeft,
                        topBaseTop + topInset,
                        topBaseRight,
                        topBaseBottom
                );
            }
            if (bottomBar != null) {
                bottomBar.setPadding(
                        bottomBaseLeft,
                        bottomBaseTop,
                        bottomBaseRight,
                        bottomBaseBottom + bottomInset
                );
            }
            return insets;
        });
        root.post(root::requestApplyInsets);
    }
}
