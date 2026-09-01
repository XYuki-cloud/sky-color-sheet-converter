package com.xyuki.skycolor.converter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xyuki.skycolor.converter.player.PlayerHomeActivity;
import com.xyuki.skycolor.converter.ui.SystemBarInsets;

/** Entry screen that keeps conversion and playback as two visibly separate workflows. */
public final class ModeSelectionActivity extends Activity {
    private static final int PAGE_BACKGROUND = Color.rgb(248, 247, 255);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int TEXT_PRIMARY = Color.rgb(49, 42, 78);
    private static final int TEXT_SECONDARY = Color.rgb(104, 96, 130);
    private static final int PRIMARY = Color.rgb(91, 73, 187);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));

        FrameLayoutRoot content = new FrameLayoutRoot();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(28));
        scrollView.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        content.addView(scrollView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setGravity(Gravity.CENTER_HORIZONTAL);
        topBar.setPadding(0, dp(8), 0, dp(18));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon_art);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        topBar.addView(icon, new LinearLayout.LayoutParams(dp(108), dp(108)));
        TextView title = textView(getString(R.string.app_name), 27, TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        topBar.addView(title, wrapParams());
        TextView subtitle = textView("选择你要使用的功能", 14, TEXT_SECONDARY);
        subtitle.setPadding(0, dp(5), 0, 0);
        topBar.addView(subtitle, wrapParams());
        body.addView(topBar, wrapParams());

        body.addView(modeCard(
                "黑白谱 → 彩谱",
                "批量导入、编辑标题并生成黑·红·蓝三层彩谱",
                "转换器",
                Color.rgb(84, 70, 174),
                view -> startActivity(new Intent(this, MainActivity.class))
        ), cardParams());
        body.addView(modeCard(
                "打开试听器",
                "导入黑白谱、TXT 或已有彩谱，按时间试听和放慢练习",
                "试听器",
                Color.rgb(80, 101, 207),
                view -> startActivity(new Intent(this, PlayerHomeActivity.class))
        ), cardParams());

        TextView footer = textView("不播放原曲 · 本机实时合成 · 不申请存储权限", 12, TEXT_SECONDARY);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        body.addView(footer, wrapParams());

        setContentView(content);
        SystemBarInsets.install(this, content, topBar, footer);
    }

    private LinearLayout modeCard(
            String title,
            String description,
            String badge,
            int accent,
            View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(156));
        card.setPadding(dp(20), dp(18), dp(20), dp(20));
        card.setBackground(roundRect(SURFACE, 22, Color.rgb(229, 225, 244), 1));
        card.setOnClickListener(listener);
        TextView badgeView = textView(badge.toUpperCase(java.util.Locale.ROOT), 11, Color.WHITE);
        badgeView.setTypeface(null, Typeface.BOLD);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setPadding(dp(10), dp(5), dp(10), dp(5));
        badgeView.setBackground(roundRect(accent, 10, 0, 0));
        card.addView(badgeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        TextView titleView = textView(title, 21, TEXT_PRIMARY);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, dp(14), 0, dp(4));
        card.addView(titleView, wrapParams());
        TextView descriptionView = textView(description, 14, TEXT_SECONDARY);
        descriptionView.setLineSpacing(0f, 1.15f);
        card.addView(descriptionView, wrapParams());
        TextView action = textView("点击进入  ›", 14, accent);
        action.setTypeface(null, Typeface.BOLD);
        action.setGravity(Gravity.RIGHT);
        action.setPadding(0, dp(16), 0, 0);
        card.addView(action, wrapParams());
        return card;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) {
            drawable.setStroke(dp(strokeWidth), stroke);
        }
        return drawable;
    }

    private TextView textView(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = wrapParams();
        params.setMargins(0, 0, 0, dp(14));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A named FrameLayout keeps this Activity independent from AndroidX containers. */
    private final class FrameLayoutRoot extends android.widget.FrameLayout {
        FrameLayoutRoot() {
            super(ModeSelectionActivity.this);
            setBackgroundColor(PAGE_BACKGROUND);
        }
    }
}
