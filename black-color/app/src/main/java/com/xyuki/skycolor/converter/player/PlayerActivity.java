package com.xyuki.skycolor.converter.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;
import com.xyuki.skycolor.converter.core.ScoreImportReader;
import com.xyuki.skycolor.converter.storage.SafDocumentStore;
import com.xyuki.skycolor.converter.ui.SystemBarInsets;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone score preview page with manual and source-time playback. */
public final class PlayerActivity extends Activity {
    public static final String EXTRA_SOURCE_URI = "source_uri";
    public static final String EXTRA_SOURCE_NAME = "source_name";
    public static final String EXTRA_SONG_INDEX = "song_index";
    public static final String EXTRA_SOURCE_KIND = "source_kind";
    public static final String EXTRA_TITLE_OVERRIDE = "title_override";
    public static final String SOURCE_KIND_BLACK = "BLACK";
    public static final String SOURCE_KIND_COLOR = "COLOR";

    private static final int ACTIVE_BLACK = Color.rgb(42, 40, 58);
    private static final int ACTIVE_RED = Color.rgb(225, 83, 105);
    private static final int ACTIVE_BLUE = Color.rgb(79, 101, 215);
    private static final int TEXT_PRIMARY = Color.rgb(49, 42, 78);
    private static final int TEXT_SECONDARY = Color.rgb(104, 96, 130);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int PAGE_BACKGROUND = Color.rgb(248, 247, 255);

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tickerPosted = false;
            if (controller != null && controller.state() == PlaybackController.State.PLAYING) {
                controller.tick(SystemClock.elapsedRealtime());
                postTicker();
            }
        }
    };

    private FrameLayout root;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleText;
    private TextView sourceText;
    private TextView layerText;
    private TextView eventMetaText;
    private TextView timeText;
    private TextView statusText;
    private GridLayout keyGrid;
    private final TextView[] keyCells = new TextView[15];
    private SeekBar progressBar;
    private SeekBar speedBar;
    private SeekBar volumeBar;
    private SeekBar transposeBar;
    private TextView speedValue;
    private TextView volumeValue;
    private TextView transposeValue;
    private TextView settingsTitle;
    private Button[] speedPresetButtons;
    private Button playButton;
    private Button stopButton;
    private Button previousButton;
    private Button nextButton;
    private boolean draggingProgress;
    private boolean tickerPosted;

    private PlaybackSequence sequence;
    private SynthAudioEngine audioEngine;
    private PlaybackController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Aurora Keys · 播放");
        buildContentView();
        loadScore();
    }

    @Override
    protected void onStop() {
        if (controller != null && controller.state() == PlaybackController.State.PLAYING) {
            controller.pause(SystemClock.elapsedRealtime());
        }
        stopTicker();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopTicker();
        if (controller != null) {
            controller.release();
            controller = null;
        }
        loader.shutdownNow();
        super.onDestroy();
    }

    private void buildContentView() {
        root = new FrameLayout(this);
        root.setBackgroundColor(PAGE_BACKGROUND);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(250));
        scrollView.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        topBar = createTopBar();
        body.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout previewCard = card();
        TextView kicker = label("CURRENT LAYER");
        previewCard.addView(kicker, wrapParams());
        layerText = textView("正在读取谱面…", 21, TEXT_PRIMARY);
        layerText.setTypeface(null, Typeface.BOLD);
        previewCard.addView(layerText, wrapParams());
        eventMetaText = textView("请稍候", 13, TEXT_SECONDARY);
        eventMetaText.setPadding(0, dp(5), 0, dp(12));
        previewCard.addView(eventMetaText, wrapParams());
        keyGrid = new GridLayout(this);
        keyGrid.setColumnCount(5);
        keyGrid.setRowCount(3);
        keyGrid.setUseDefaultMargins(false);
        for (int index = 0; index < BlackScoreReader.KEY_LABELS.length; index++) {
            TextView cell = textView(BlackScoreReader.KEY_LABELS[index], 13, TEXT_SECONDARY);
            cell.setGravity(Gravity.CENTER);
            cell.setTypeface(null, Typeface.BOLD);
            cell.setBackground(roundRect(Color.rgb(242, 240, 250), 12, 0, 0));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(index / 5, 1f),
                    GridLayout.spec(index % 5, 1f)
            );
            params.width = 0;
            params.height = dp(58);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            keyGrid.addView(cell, params);
            keyCells[index] = cell;
        }
        previewCard.addView(keyGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        body.addView(previewCard, cardParams());

        LinearLayout timelineCard = card();
        TextView timelineTitle = textView("播放进度", 16, TEXT_PRIMARY);
        timelineTitle.setTypeface(null, Typeface.BOLD);
        timelineCard.addView(timelineTitle, wrapParams());
        LinearLayout timeLine = new LinearLayout(this);
        timeLine.setGravity(Gravity.CENTER_VERTICAL);
        timeText = textView("00:00 / 00:00", 12, TEXT_SECONDARY);
        timeLine.addView(timeText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        timelineCard.addView(timeLine, wrapParams());
        progressBar = new SeekBar(this);
        progressBar.setMax(1);
        progressBar.setEnabled(false);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    timeText.setText(formatTime(progress) + " / "
                            + formatTime(sequence == null ? 0L : sequence.durationMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                draggingProgress = true;
                if (controller != null && controller.state() == PlaybackController.State.PLAYING) {
                    controller.pause(SystemClock.elapsedRealtime());
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                draggingProgress = false;
                if (controller != null && controller.seekTo(seekBar.getProgress())) {
                    statusText.setText("已静默定位，点击播放继续");
                }
            }
        });
        timelineCard.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        ));
        body.addView(timelineCard, cardParams());

        LinearLayout settingsCard = card();
        settingsTitle = textView("声音设置 · 1×  ▾", 16, TEXT_PRIMARY);
        settingsTitle.setTypeface(null, Typeface.BOLD);
        settingsTitle.setGravity(Gravity.CENTER_VERTICAL);
        settingsTitle.setMinHeight(dp(48));
        settingsCard.addView(settingsTitle, wrapParams());
        LinearLayout settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setVisibility(View.GONE);
        settingsCard.addView(settingsContent, wrapParams());
        settingsTitle.setOnClickListener(view -> {
            boolean expanded = settingsContent.getVisibility() != View.VISIBLE;
            settingsContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
            updateSettingsTitle(expanded);
        });

        TextView speedHint = textView("自动播放速度（保持原音高）", 13, TEXT_SECONDARY);
        speedHint.setPadding(0, dp(4), 0, 0);
        settingsContent.addView(speedHint, wrapParams());
        LinearLayout speedRow = sliderRow("倍速", 0);
        speedBar = (SeekBar) speedRow.getTag();
        speedBar.setMax(PlaybackSpeed.SLIDER_MAX);
        speedBar.setContentDescription("自动播放倍速");
        speedValue = (TextView) speedRow.getChildAt(2);
        speedValue.setText(PlaybackSpeed.label(PlaybackSpeed.MAX_MULTIPLIER));
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double multiplier = PlaybackSpeed.fromSlider(progress);
                speedValue.setText(PlaybackSpeed.label(multiplier));
                updateSettingsTitle(settingsContent.getVisibility() == View.VISIBLE);
                if (controller != null) {
                    controller.setSpeed(multiplier, SystemClock.elapsedRealtime());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        settingsContent.addView(speedRow, wrapParams());

        GridLayout presets = new GridLayout(this);
        presets.setColumnCount(4);
        presets.setRowCount(2);
        presets.setUseDefaultMargins(false);
        speedPresetButtons = new Button[PlaybackSpeed.PRESETS.length];
        for (int index = 0; index < PlaybackSpeed.PRESETS.length; index++) {
            Button preset = controlButton(PlaybackSpeed.label(PlaybackSpeed.PRESETS[index]));
            preset.setTextSize(12);
            int row = index / 4;
            int column = index % 4;
            GridLayout.LayoutParams presetParams = new GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(column, 1f)
            );
            presetParams.width = 0;
            presetParams.height = dp(48);
            presetParams.setMargins(dp(2), dp(2), dp(2), dp(2));
            presets.addView(preset, presetParams);
            speedPresetButtons[index] = preset;
            final double multiplier = PlaybackSpeed.PRESETS[index];
            preset.setOnClickListener(view -> speedBar.setProgress(
                    PlaybackSpeed.toSlider(multiplier)
            ));
        }
        settingsContent.addView(presets, wrapParams());

        LinearLayout volumeRow = sliderRow("音量", 68);
        volumeBar = (SeekBar) volumeRow.getTag();
        volumeValue = (TextView) volumeRow.getChildAt(2);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
                if (controller != null) {
                    controller.setVolume(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        settingsContent.addView(volumeRow, wrapParams());
        LinearLayout transposeRow = sliderRow("移调", 12);
        transposeBar = (SeekBar) transposeRow.getTag();
        transposeValue = (TextView) transposeRow.getChildAt(2);
        transposeValue.setText("0 半音");
        transposeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int transpose = progress - 12;
                transposeValue.setText((transpose > 0 ? "+" : "") + transpose + " 半音");
                if (controller != null) {
                    controller.setTranspose(transpose);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        settingsContent.addView(transposeRow, wrapParams());
        body.addView(settingsCard, cardParams());

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(14), dp(10), dp(14), dp(12));
        bottomBar.setBackground(roundRect(SURFACE, 20, Color.rgb(229, 225, 244), 1));
        bottomBar.setElevation(dp(8));
        statusText = textView("正在准备试听器…", 12, TEXT_SECONDARY);
        statusText.setMaxLines(2);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        bottomBar.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        previousButton = controlButton("上一层");
        stopButton = controlButton("停止");
        playButton = primaryButton("自动播放");
        nextButton = controlButton("下一层");
        controls.addView(previousButton, controlParams());
        controls.addView(stopButton, controlParams());
        controls.addView(playButton, new LinearLayout.LayoutParams(
                0, dp(48), 1.25f
        ));
        controls.addView(nextButton, controlParams());
        bottomBar.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        ));
        previousButton.setOnClickListener(view -> {
            if (controller != null) {
                controller.stepPrevious();
            }
        });
        nextButton.setOnClickListener(view -> {
            if (controller != null) {
                controller.stepNext();
            }
        });
        stopButton.setOnClickListener(view -> {
            if (controller != null) {
                controller.stop();
            }
            stopTicker();
        });
        playButton.setOnClickListener(view -> togglePlay());
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        setContentView(root);
        SystemBarInsets.install(this, root, topBar, bottomBar);
        setControlsEnabled(false);
    }

    private LinearLayout createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(12));
        Button back = controlButton("‹");
        back.setTextSize(28);
        back.setContentDescription("返回");
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(view -> finish());
        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(8), 0, 0, 0);
        titleText = textView("彩谱试听", 22, TEXT_PRIMARY);
        titleText.setTypeface(null, Typeface.BOLD);
        textColumn.addView(titleText, wrapParams());
        sourceText = textView("正在读取…", 12, TEXT_SECONDARY);
        sourceText.setSingleLine(true);
        sourceText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textColumn.addView(sourceText, wrapParams());
        bar.addView(textColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        return bar;
    }

    private void loadScore() {
        String uriText = getIntent().getStringExtra(EXTRA_SOURCE_URI);
        String sourceName = getIntent().getStringExtra(EXTRA_SOURCE_NAME);
        String sourceKind = getIntent().getStringExtra(EXTRA_SOURCE_KIND);
        int songIndex = getIntent().getIntExtra(EXTRA_SONG_INDEX, 0);
        String titleOverride = getIntent().getStringExtra(EXTRA_TITLE_OVERRIDE);
        if (uriText == null || uriText.trim().isEmpty()) {
            showLoadError("缺少输入谱面路径");
            return;
        }
        sourceText.setText(sourceName == null ? "正在读取…" : sourceName);
        loader.execute(() -> {
            try {
                Uri sourceUri = Uri.parse(uriText);
                SafDocumentStore store = new SafDocumentStore(getContentResolver());
                byte[] bytes = store.readBytes(sourceUri);
                PlaybackSequence loaded;
                List<ScoreImportReader.ImportedScore> imported = ScoreImportReader.read(
                        bytes,
                        sourceName
                );
                if (SOURCE_KIND_COLOR.equals(sourceKind)) {
                    ScoreImportReader.ImportedScore color = imported.get(0);
                    if (color.kind != ScoreImportReader.Kind.COLOR_PREVIEW
                            || color.colorDocument == null) {
                        throw new IllegalArgumentException(color.error.isEmpty()
                                ? "输入不是有效的 sky-color-v1 彩谱" : color.error);
                    }
                    loaded = PlaybackSequence.fromColorDocument(color.colorDocument);
                } else {
                    if (songIndex < 0 || songIndex >= imported.size()) {
                        throw new IllegalArgumentException("歌曲序号超出文件内容范围");
                    }
                    ScoreImportReader.ImportedScore black = imported.get(songIndex);
                    if (black.kind != ScoreImportReader.Kind.BLACK
                            || black.blackDocument == null) {
                        throw new IllegalArgumentException(black.error.isEmpty()
                                ? "输入不是有效的黑白谱" : black.error);
                    }
                    ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(
                            black.blackDocument,
                            titleOverride
                    );
                    loaded = PlaybackSequence.fromConversion(conversion);
                }
                mainHandler.post(() -> bindSequence(loaded));
            } catch (Exception exception) {
                mainHandler.post(() -> showLoadError(messageOf(exception)));
            }
        });
    }

    private void bindSequence(PlaybackSequence loaded) {
        sequence = loaded;
        titleText.setText(sequence.title);
        sourceText.setText(sequence.sourceName + " · " + sequence.events.size() + " 个播放层");
        audioEngine = new SynthAudioEngine(this);
        controller = new PlaybackController(sequence, audioEngine, new PlaybackController.Listener() {
            @Override
            public void onStateChanged(PlaybackController.State state) {
                mainHandler.post(() -> renderState(state));
            }

            @Override
            public void onEvent(PlaybackEvent event) {
                mainHandler.post(() -> renderEvent(event));
            }

            @Override
            public void onPositionChanged(long positionMs, int currentEventIndex) {
                mainHandler.post(() -> renderPosition(positionMs));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> statusText.setText(message));
            }
        });
        progressBar.setMax((int) Math.min(Integer.MAX_VALUE, sequence.durationMs));
        progressBar.setProgress(0);
        progressBar.setEnabled(sequence.hasTimedPlayback());
        setControlsEnabled(!sequence.events.isEmpty());
        if (sequence.hasTimedPlayback()) {
            statusText.setText("按时间自动播放 · 当前 "
                    + PlaybackSpeed.label(controller.speedMultiplier())
                    + " · 也可以逐层手动试听");
        } else {
            statusText.setText("缺少有效 source_time，只能手动试听");
        }
        updateSettingsTitle(false);
        renderPosition(0L);
        renderEvent(null);
    }

    private void togglePlay() {
        if (controller == null) {
            return;
        }
        if (controller.state() == PlaybackController.State.PLAYING) {
            controller.pause(SystemClock.elapsedRealtime());
            stopTicker();
        } else if (controller.start(SystemClock.elapsedRealtime())) {
            postTicker();
        }
    }

    private void renderState(PlaybackController.State state) {
        if (playButton == null) {
            return;
        }
        if (state == PlaybackController.State.PLAYING) {
            playButton.setText("暂停");
            postTicker();
        } else {
            playButton.setText(state == PlaybackController.State.COMPLETED ? "重播" : "自动播放");
            if (state != PlaybackController.State.PLAYING) {
                stopTicker();
            }
            if (state == PlaybackController.State.COMPLETED) {
                statusText.setText("播放完成，点击重播");
            }
        }
    }

    private void renderEvent(PlaybackEvent event) {
        if (event == null) {
            layerText.setText("尚未播放");
            eventMetaText.setText(sequence == null || sequence.events.isEmpty()
                    ? "没有可播放音符" : "点击播放或下一层开始");
            setGridColors(null);
            return;
        }
        layerText.setText(colorLabel(event.color));
        String sourceTime = event.sourceTime == null
                ? "无 source_time" : "source_time=" + event.sourceTime + " ms";
        eventMetaText.setText("第 " + (event.imageIndex + 1) + " 张图 · 第 "
                + (event.layerIndex + 1) + " 层 · 源帧 " + event.sourceFrameIndex
                + " · " + sourceTime + "\n按键：" + String.join(" ", event.keys));
        setGridColors(event);
    }

    private void renderPosition(long positionMs) {
        long duration = sequence == null ? 0L : sequence.durationMs;
        if (!draggingProgress && progressBar != null && sequence != null && sequence.hasTimedPlayback()) {
            progressBar.setProgress((int) Math.min(Integer.MAX_VALUE, Math.max(0L, positionMs)));
        }
        if (timeText != null) {
            timeText.setText(formatTime(positionMs) + " / " + formatTime(duration));
        }
    }

    private void setGridColors(PlaybackEvent event) {
        for (int index = 0; index < keyCells.length; index++) {
            TextView cell = keyCells[index];
            boolean active = event != null && event.keys.contains(BlackScoreReader.KEY_LABELS[index]);
            if (active) {
                int color = activeColor(event.color);
                cell.setBackground(roundRect(color, 12, 0, 0));
                cell.setTextColor(Color.WHITE);
            } else {
                cell.setBackground(roundRect(Color.rgb(242, 240, 250), 12, 0, 0));
                cell.setTextColor(TEXT_SECONDARY);
            }
        }
    }

    private void setControlsEnabled(boolean enabled) {
        if (playButton == null) {
            return;
        }
        boolean timed = enabled && sequence != null && sequence.hasTimedPlayback();
        playButton.setEnabled(timed);
        stopButton.setEnabled(enabled);
        previousButton.setEnabled(enabled);
        nextButton.setEnabled(enabled);
        progressBar.setEnabled(timed);
        speedBar.setEnabled(timed);
        if (speedPresetButtons != null) {
            for (Button button : speedPresetButtons) {
                button.setEnabled(timed);
            }
        }
        volumeBar.setEnabled(enabled);
        transposeBar.setEnabled(enabled);
    }

    private void updateSettingsTitle(boolean expanded) {
        if (settingsTitle == null || speedValue == null) {
            return;
        }
        settingsTitle.setText("声音设置 · " + speedValue.getText()
                + (expanded ? "  ▴" : "  ▾"));
    }

    private void showLoadError(String message) {
        setControlsEnabled(false);
        titleText.setText("无法载入谱面");
        eventMetaText.setText(message);
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void postTicker() {
        if (!tickerPosted) {
            tickerPosted = true;
            mainHandler.postDelayed(ticker, 25L);
        }
    }

    private void stopTicker() {
        tickerPosted = false;
        mainHandler.removeCallbacks(ticker);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(16));
        card.setBackground(roundRect(SURFACE, 20, 0, 0));
        return card;
    }

    private LinearLayout sliderRow(String name, int initial) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, 0);
        TextView label = textView(name, 13, TEXT_SECONDARY);
        row.addView(label, new LinearLayout.LayoutParams(dp(44), dp(46)));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(name.equals("移调") ? 24 : 100);
        seekBar.setProgress(initial);
        row.addView(seekBar, new LinearLayout.LayoutParams(
                0, dp(46), 1f
        ));
        TextView value = textView(name.equals("移调") ? "0 半音" : initial + "%", 12, TEXT_SECONDARY);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(dp(62), dp(46)));
        row.setTag(seekBar);
        return row;
    }

    private Button controlButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(TEXT_PRIMARY);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(roundRect(Color.rgb(242, 240, 250), 14, 0, 0));
        return button;
    }

    private Button primaryButton(String label) {
        Button button = controlButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(Color.rgb(91, 73, 187), 14, 0, 0));
        return button;
    }

    private LinearLayout.LayoutParams controlParams() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private TextView label(String value) {
        TextView view = textView(value, 11, Color.rgb(119, 106, 176));
        view.setTypeface(null, Typeface.BOLD);
        return view;
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
        params.setMargins(0, 0, 0, dp(12));
        return params;
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

    private int activeColor(String color) {
        if ("red".equalsIgnoreCase(color)) {
            return ACTIVE_RED;
        }
        if ("blue".equalsIgnoreCase(color)) {
            return ACTIVE_BLUE;
        }
        return ACTIVE_BLACK;
    }

    private String colorLabel(String color) {
        if ("red".equalsIgnoreCase(color)) {
            return "红层";
        }
        if ("blue".equalsIgnoreCase(color)) {
            return "蓝层";
        }
        return "黑层";
    }

    private String formatTime(long milliseconds) {
        long seconds = Math.max(0L, milliseconds) / 1000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }
}
