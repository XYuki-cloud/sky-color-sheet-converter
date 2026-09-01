package com.xyuki.skycolor.converter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xyuki.skycolor.converter.batch.BatchProcessor;
import com.xyuki.skycolor.converter.core.ScoreImportReader;
import com.xyuki.skycolor.converter.player.PlayerActivity;
import com.xyuki.skycolor.converter.storage.SafDocumentStore;
import com.xyuki.skycolor.converter.ui.SystemBarInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-screen batch converter; valid items can open the standalone player. */
public final class MainActivity extends Activity {
    private static final int REQUEST_FILES = 1001;
    private static final int REQUEST_INPUT_TREE = 1002;
    private static final int REQUEST_OUTPUT_TREE = 1003;
    private static final String PREFS = "sky_color_converter";
    private static final String PREF_INPUT_TREE = "input_tree";
    private static final String PREF_OUTPUT_TREE = "output_tree";

    private static final int PAGE_BACKGROUND = Color.rgb(248, 247, 255);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int TEXT_PRIMARY = Color.rgb(49, 42, 78);
    private static final int TEXT_SECONDARY = Color.rgb(104, 96, 130);
    private static final int PRIMARY = Color.rgb(91, 73, 187);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final ArrayList<BatchProcessor.InputItem> items = new ArrayList<>();
    private final Map<String, String> titleOverrides = new LinkedHashMap<>();

    private ListView songList;
    private ScoreListAdapter adapter;
    private Button chooseFilesButton;
    private Button chooseInputFolderButton;
    private Button chooseOutputButton;
    private Button generateButton;
    private Button cancelButton;
    private TextView importSummary;
    private TextView outputSummary;
    private TextView statusText;
    private TextView logText;
    private ProgressBar progressBar;
    private Uri inputTreeUri;
    private Uri outputTreeUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));
        buildContentView();
        restoreLastOutput();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_FILES) {
            List<Uri> selected = selectedUris(data);
            if (!selected.isEmpty()) {
                persistGrant(data, selected);
                inputTreeUri = null;
                savePreference(PREF_INPUT_TREE, null);
                loadFiles(selected);
            }
        } else if (requestCode == REQUEST_INPUT_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                persistGrant(data, Collections.singletonList(tree));
                inputTreeUri = tree;
                savePreference(PREF_INPUT_TREE, tree.toString());
                loadFolder(tree);
            }
        } else if (requestCode == REQUEST_OUTPUT_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                if (sameTree(tree, inputTreeUri)) {
                    showToast("输入文件夹和输出文件夹不能相同");
                    return;
                }
                persistGrant(data, Collections.singletonList(tree));
                outputTreeUri = tree;
                savePreference(PREF_OUTPUT_TREE, tree.toString());
                outputSummary.setText("输出位置：已选择文件夹");
                statusText.setText("输出文件夹已准备好，可以生成彩谱");
            }
        }
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BACKGROUND);

        LinearLayout topSection = new LinearLayout(this);
        topSection.setOrientation(LinearLayout.VERTICAL);
        topSection.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = secondaryButton("‹");
        backButton.setTextSize(28);
        backButton.setContentDescription("返回功能选择");
        header.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        backButton.setOnClickListener(view -> finish());
        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(9), 0, 0, 0);
        TextView title = textView(getString(R.string.app_name), 25, TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        titleColumn.addView(title, wrapParams());
        TextView subtitle = textView("批量导入 · 自定义标题 · 一键导出 · 即时试听", 13, TEXT_SECONDARY);
        subtitle.setPadding(0, dp(3), 0, dp(4));
        titleColumn.addView(subtitle, wrapParams());
        header.addView(titleColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        topSection.addView(header, wrapParams());

        LinearLayout importCard = card();
        TextView importKicker = textView("IMPORT SCORES", 11, Color.rgb(119, 106, 176));
        importKicker.setTypeface(null, Typeface.BOLD);
        importCard.addView(importKicker, wrapParams());
        TextView importHint = textView("支持黑白 JSON、结构化 TXT 和已有彩谱试听", 13, TEXT_SECONDARY);
        importHint.setPadding(0, dp(4), 0, dp(10));
        importCard.addView(importHint, wrapParams());
        LinearLayout inputButtons = new LinearLayout(this);
        inputButtons.setGravity(Gravity.CENTER_VERTICAL);
        chooseFilesButton = secondaryButton("选择多个文件");
        chooseInputFolderButton = secondaryButton("选择输入文件夹");
        inputButtons.addView(chooseFilesButton, weightedButtonParams());
        inputButtons.addView(chooseInputFolderButton, weightedButtonParams());
        importCard.addView(inputButtons, wrapParams());
        chooseFilesButton.setOnClickListener(view -> openFiles());
        chooseInputFolderButton.setOnClickListener(view -> openInputFolder());
        importSummary = textView("尚未导入文件", 12, TEXT_SECONDARY);
        importSummary.setPadding(0, dp(9), 0, 0);
        importCard.addView(importSummary, wrapParams());
        topSection.addView(importCard, wrapParams());
        root.addView(topSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        songList = new ListView(this);
        songList.setDividerHeight(dp(8));
        songList.setDivider(null);
        songList.setBackgroundColor(PAGE_BACKGROUND);
        songList.setPadding(dp(16), dp(4), dp(16), dp(8));
        songList.setClipToPadding(false);
        adapter = new ScoreListAdapter();
        songList.setAdapter(adapter);
        root.addView(songList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(12));
        bottomBar.setBackground(roundRect(SURFACE, 20, Color.rgb(229, 225, 244), 1));
        bottomBar.setElevation(dp(8));
        chooseOutputButton = secondaryButton("选择输出文件夹");
        bottomBar.addView(chooseOutputButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        chooseOutputButton.setOnClickListener(view -> openOutputFolder());
        outputSummary = textView("输出位置：未选择（推荐单独建一个空文件夹）", 12, TEXT_SECONDARY);
        outputSummary.setSingleLine(true);
        outputSummary.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        outputSummary.setPadding(0, dp(5), 0, dp(5));
        bottomBar.addView(outputSummary, wrapParams());

        LinearLayout actionButtons = new LinearLayout(this);
        actionButtons.setGravity(Gravity.CENTER_VERTICAL);
        generateButton = primaryButton("生成彩谱");
        cancelButton = secondaryButton("取消");
        cancelButton.setEnabled(false);
        actionButtons.addView(generateButton, new LinearLayout.LayoutParams(
                0, dp(48), 2f
        ));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f
        );
        cancelParams.setMargins(dp(8), 0, 0, 0);
        actionButtons.addView(cancelButton, cancelParams);
        bottomBar.addView(actionButtons, wrapParams());
        generateButton.setOnClickListener(view -> generate());
        cancelButton.setOnClickListener(view -> {
            cancelRequested.set(true);
            statusText.setText("已请求取消，将在当前歌曲完成后停止");
            cancelButton.setEnabled(false);
        });

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        bottomBar.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
        ));
        statusText = textView("等待导入黑白谱", 12, Color.rgb(67, 53, 108));
        statusText.setPadding(0, dp(6), 0, 0);
        statusText.setMaxLines(2);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        bottomBar.addView(statusText, wrapParams());
        logText = textView("", 11, TEXT_SECONDARY);
        logText.setMaxLines(2);
        logText.setEllipsize(TextUtils.TruncateAt.END);
        bottomBar.addView(logText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
        SystemBarInsets.install(this, root, topSection, bottomBar);
    }

    private void openFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FILES);
    }

    private void openInputFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_INPUT_TREE);
    }

    private void openOutputFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OUTPUT_TREE);
    }

    private void loadFiles(List<Uri> uris) {
        setImporting(true);
        statusText.setText("正在读取和检查文件……");
        worker.execute(() -> {
            SafDocumentStore store = new SafDocumentStore(getContentResolver());
            List<BatchProcessor.InputItem> loaded = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Uri uri : uris) {
                if (uri == null || !seen.add(uri.toString())) {
                    continue;
                }
                String name = store.displayName(uri);
                loaded.addAll(parseOneUri(store, uri, name));
            }
            mainHandler.post(() -> applyLoadedItems(loaded, "文件"));
        });
    }

    private void loadFolder(Uri tree) {
        setImporting(true);
        statusText.setText("正在递归扫描文件夹……");
        worker.execute(() -> {
            List<BatchProcessor.InputItem> loaded = new ArrayList<>();
            try {
                SafDocumentStore store = new SafDocumentStore(getContentResolver());
                for (SafDocumentStore.Entry entry : store.findInputFiles(tree)) {
                    loaded.addAll(parseOneUri(store, entry.uri, entry.name));
                }
                mainHandler.post(() -> applyLoadedItems(loaded, "文件夹"));
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setImporting(false);
                    statusText.setText("扫描失败：" + messageOf(exception));
                    importSummary.setText("未能读取输入文件夹");
                });
            }
        });
    }

    private List<BatchProcessor.InputItem> parseOneUri(
            SafDocumentStore store,
            Uri uri,
            String sourceName
    ) {
        try {
            byte[] bytes = store.readBytes(uri);
            List<BatchProcessor.InputItem> result = new ArrayList<>();
            for (ScoreImportReader.ImportedScore imported
                    : ScoreImportReader.read(bytes, sourceName)) {
                if (imported.kind == ScoreImportReader.Kind.BLACK) {
                    result.add(BatchProcessor.InputItem.success(
                            uri,
                            sourceName,
                            imported.songIndex,
                            imported.songCount,
                            imported.blackDocument
                    ));
                } else if (imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW) {
                    result.add(BatchProcessor.InputItem.previewOnly(
                            uri,
                            sourceName,
                            imported.colorDocument
                    ));
                } else {
                    result.add(BatchProcessor.InputItem.failure(
                            uri,
                            sourceName,
                            imported.error
                    ));
                }
            }
            return result;
        } catch (Exception exception) {
            return Collections.singletonList(
                    BatchProcessor.InputItem.failure(uri, sourceName, messageOf(exception))
            );
        }
    }

    private void applyLoadedItems(List<BatchProcessor.InputItem> loaded, String sourceKind) {
        setImporting(false);
        items.clear();
        titleOverrides.clear();
        if (loaded != null) {
            items.addAll(loaded);
        }
        adapter.notifyDataSetChanged();
        int generatable = 0;
        int previewOnly = 0;
        int invalid = 0;
        for (BatchProcessor.InputItem item : items) {
            if (item.isPreviewOnly()) {
                previewOnly++;
            } else if (item.isGeneratable()) {
                generatable++;
            } else {
                invalid++;
            }
        }
        importSummary.setText("已导入 " + items.size() + " 首：可生成 " + generatable
                + " · 可试听 " + (generatable + previewOnly) + " · 错误 " + invalid
                + "（" + sourceKind + "模式）");
        if (items.isEmpty()) {
            statusText.setText("没有找到 .json 或 .txt 文件");
        } else if (invalid > 0) {
            statusText.setText("红色项目会显示错误原因；有效黑白谱可直接试听或生成");
        } else {
            statusText.setText("点击每首歌曲的“试听”，或修改标题后批量生成");
        }
    }

    private void openPlayer(BatchProcessor.InputItem item) {
        if (item == null || item.sourceUri == null || (!item.isGeneratable() && !item.isPreviewOnly())) {
            showToast("此项目没有可试听的有效谱面");
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_URI, item.sourceUri.toString());
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_NAME, item.sourceName);
        intent.putExtra(PlayerActivity.EXTRA_SONG_INDEX, item.songIndex);
        intent.putExtra(
                PlayerActivity.EXTRA_SOURCE_KIND,
                item.isPreviewOnly() ? PlayerActivity.SOURCE_KIND_COLOR : PlayerActivity.SOURCE_KIND_BLACK
        );
        String override = titleOverrides.get(item.id());
        if (override != null && !override.trim().isEmpty()) {
            intent.putExtra(PlayerActivity.EXTRA_TITLE_OVERRIDE, override);
        }
        startActivity(intent);
    }

    private void generate() {
        songList.clearFocus();
        if (items.isEmpty()) {
            showToast("请先选择至少一个输入文件或文件夹");
            return;
        }
        boolean hasGeneratable = false;
        for (BatchProcessor.InputItem item : items) {
            if (item.isGeneratable()) {
                hasGeneratable = true;
                break;
            }
        }
        if (!hasGeneratable) {
            showToast("当前没有可生成的黑白谱；已有彩谱只能试听");
            return;
        }
        if (outputTreeUri == null) {
            showToast("请先选择输出文件夹");
            return;
        }
        if (sameTree(outputTreeUri, inputTreeUri)) {
            showToast("输入文件夹和输出文件夹不能相同");
            return;
        }
        List<BatchProcessor.InputItem> snapshot = new ArrayList<>(items);
        Map<String, String> overrides = new LinkedHashMap<>(titleOverrides);
        cancelRequested.set(false);
        setRunning(true, snapshot.size());
        worker.execute(() -> {
            try {
                BatchProcessor.run(
                        getContentResolver(),
                        snapshot,
                        outputTreeUri,
                        overrides,
                        new BatchProcessor.ProgressListener() {
                            @Override
                            public void onStarted(int total) {
                                postStatus("开始生成，共 " + total + " 项");
                            }

                            @Override
                            public void onProgress(int completed, int total, String message) {
                                mainHandler.post(() -> {
                                    progressBar.setProgress(total <= 0
                                            ? 0 : completed * 100 / total);
                                    statusText.setText(completed + "/" + total + "：" + message);
                                });
                            }

                            @Override
                            public void onLog(String message) {
                                postLog(message);
                            }

                            @Override
                            public void onFinished(BatchProcessor.BatchSummary summary) {
                                mainHandler.post(() -> finishRun(summary));
                            }
                        },
                        cancelRequested::get
                );
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setRunning(false, 0);
                    statusText.setText("生成失败：" + messageOf(exception));
                });
            }
        });
    }

    private void finishRun(BatchProcessor.BatchSummary summary) {
        setRunning(false, 0);
        statusText.setText("完成：成功 " + summary.successCount + "，失败 "
                + summary.failedCount + "，跳过 " + summary.skippedCount);
        outputSummary.setText("输出位置：已写入所选文件夹");
        if (summary.failedCount > 0 || summary.skippedCount > 0) {
            postLog("部分任务未生成；彩谱试听项和取消项会计为跳过");
        } else {
            postLog("全部黑白谱已生成，每首歌曲一个独立输出文件夹");
        }
    }

    private void restoreLastOutput() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String output = preferences.getString(PREF_OUTPUT_TREE, "");
        if (output != null && !output.trim().isEmpty()) {
            outputTreeUri = Uri.parse(output);
            outputSummary.setText("输出位置：已恢复上次选择的文件夹");
        }
        String input = preferences.getString(PREF_INPUT_TREE, "");
        if (input != null && !input.trim().isEmpty()) {
            inputTreeUri = Uri.parse(input);
        }
    }

    private void setImporting(boolean importing) {
        chooseFilesButton.setEnabled(!importing);
        chooseInputFolderButton.setEnabled(!importing);
        chooseOutputButton.setEnabled(!importing);
        generateButton.setEnabled(!importing);
    }

    private void setRunning(boolean running, int total) {
        chooseFilesButton.setEnabled(!running);
        chooseInputFolderButton.setEnabled(!running);
        chooseOutputButton.setEnabled(!running);
        generateButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        songList.setEnabled(!running);
        progressBar.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            progressBar.setMax(100);
            progressBar.setProgress(total == 0 ? 0 : 1);
            logText.setText("");
        }
    }

    private void postStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    private void postLog(String message) {
        mainHandler.post(() -> {
            String previous = logText.getText().toString().trim();
            logText.setText(previous.isEmpty() ? message : previous + "\n" + message);
        });
    }

    private void persistGrant(Intent data, List<Uri> uris) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        for (Uri uri : uris) {
            SafDocumentStore.takePersistablePermission(getContentResolver(), uri, flags);
        }
    }

    private List<Uri> selectedUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                result.add(data.getClipData().getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            result.add(data.getData());
        }
        return result;
    }

    private boolean sameTree(Uri first, Uri second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        try {
            return DocumentsContract.getTreeDocumentId(first).equals(
                    DocumentsContract.getTreeDocumentId(second)
            ) && first.getAuthority() != null
                    && first.getAuthority().equals(second.getAuthority());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void savePreference(String key, String value) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
        editor.apply();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(14));
        card.setBackground(roundRect(SURFACE, 18, 0, 0));
        return card;
    }

    private Button secondaryButton(String label) {
        Button button = button(label);
        button.setTextColor(TEXT_PRIMARY);
        button.setBackground(roundRect(Color.rgb(241, 239, 250), 14, 0, 0));
        return button;
    }

    private Button primaryButton(String label) {
        Button button = button(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(PRIMARY, 14, 0, 0));
        return button;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
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

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    private final class ScoreListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            BatchProcessor.InputItem item = items.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(13), dp(12), dp(13), dp(12));
            row.setBackground(roundRect(SURFACE, 18, 0, 0));

            LinearLayout heading = new LinearLayout(MainActivity.this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView type = textView(itemType(item), 11, Color.WHITE);
            type.setGravity(Gravity.CENTER);
            type.setTypeface(null, Typeface.BOLD);
            type.setPadding(dp(8), dp(4), dp(8), dp(4));
            type.setBackground(roundRect(itemTypeColor(item), 10, 0, 0));
            heading.addView(type, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            TextView source = textView(sourceLabel(item), 12,
                    item.error.trim().isEmpty() ? TEXT_SECONDARY : Color.rgb(190, 55, 74));
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            sourceParams.setMargins(dp(8), 0, dp(5), 0);
            heading.addView(source, sourceParams);
            Button previewButton = secondaryButton(
                    item.isGeneratable() || item.isPreviewOnly() ? "试听" : "不可用"
            );
            previewButton.setTextSize(12);
            previewButton.setEnabled(item.isGeneratable() || item.isPreviewOnly());
            previewButton.setOnClickListener(view -> openPlayer(item));
            heading.addView(previewButton, new LinearLayout.LayoutParams(dp(62), dp(42)));
            row.addView(heading, wrapParams());

            if (item.isGeneratable()) {
                EditText title = new EditText(MainActivity.this);
                title.setSingleLine(true);
                title.setTextSize(16);
                title.setTextColor(TEXT_PRIMARY);
                title.setHintTextColor(Color.rgb(156, 149, 178));
                title.setHint("标题（留空恢复默认）");
                title.setPadding(dp(10), 0, dp(10), 0);
                title.setBackground(roundRect(Color.rgb(250, 249, 255), 12,
                        Color.rgb(229, 225, 244), 1));
                String custom = titleOverrides.get(item.id());
                title.setText(custom == null ? item.defaultTitle() : custom);
                title.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence text, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable editable) {
                        String value = editable.toString().trim();
                        if (value.isEmpty() || value.equals(item.defaultTitle().trim())) {
                            titleOverrides.remove(item.id());
                        } else {
                            titleOverrides.put(item.id(), value);
                        }
                    }
                });
                title.setOnFocusChangeListener((view, hasFocus) -> {
                    if (!hasFocus && title.getText().toString().trim().isEmpty()) {
                        title.setText(item.defaultTitle());
                    }
                });
                LinearLayout.LayoutParams titleParams = wrapParams();
                titleParams.setMargins(0, dp(9), 0, 0);
                row.addView(title, titleParams);
            } else if (item.isPreviewOnly()) {
                TextView previewOnly = textView(item.defaultTitle() + " · 仅试听", 16, TEXT_PRIMARY);
                previewOnly.setTypeface(null, Typeface.BOLD);
                previewOnly.setPadding(0, dp(9), 0, 0);
                row.addView(previewOnly, wrapParams());
            } else {
                TextView error = textView("错误：" + item.error, 13, Color.rgb(190, 55, 74));
                error.setPadding(0, dp(9), 0, 0);
                error.setMaxLines(2);
                error.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(error, wrapParams());
            }
            return row;
        }

        private String itemType(BatchProcessor.InputItem item) {
            if (item.isPreviewOnly()) {
                return "彩谱试听";
            }
            if (item.isGeneratable()) {
                return "黑白谱";
            }
            return "错误";
        }

        private int itemTypeColor(BatchProcessor.InputItem item) {
            if (item.isPreviewOnly()) {
                return Color.rgb(112, 91, 198);
            }
            if (item.isGeneratable()) {
                return Color.rgb(55, 55, 72);
            }
            return Color.rgb(190, 55, 74);
        }

        private String sourceLabel(BatchProcessor.InputItem item) {
            String label = item.sourceName;
            if (item.songCount > 1) {
                label += " · 第 " + (item.songIndex + 1) + "/" + item.songCount + " 首";
            }
            return label;
        }
    }
}
