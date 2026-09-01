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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.xyuki.skycolor.converter.core.ScoreImportReader;
import com.xyuki.skycolor.converter.storage.SafDocumentStore;
import com.xyuki.skycolor.converter.ui.SystemBarInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent import page for listening to black, legacy TXT, and color scores. */
public final class PlayerHomeActivity extends Activity {
    private static final int REQUEST_FILES = 2001;
    private static final int REQUEST_INPUT_TREE = 2002;
    private static final int PAGE_BACKGROUND = Color.rgb(248, 247, 255);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int TEXT_PRIMARY = Color.rgb(49, 42, 78);
    private static final int TEXT_SECONDARY = Color.rgb(104, 96, 130);
    private static final int PRIMARY = Color.rgb(91, 73, 187);
    private static final int ERROR = Color.rgb(190, 63, 89);
    private static final int COLOR_BADGE = Color.rgb(80, 101, 207);
    private static final int BLACK_BADGE = Color.rgb(84, 70, 174);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<PlayableItem> items = new ArrayList<>();

    private ListView songList;
    private ScoreListAdapter adapter;
    private Button chooseFilesButton;
    private Button chooseInputFolderButton;
    private TextView importSummary;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Aurora Keys · 试听器");
        buildContentView();
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
                loadFiles(selected);
            }
        } else if (requestCode == REQUEST_INPUT_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                persistGrant(data, Collections.singletonList(tree));
                loadFolder(tree);
            }
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BACKGROUND);

        LinearLayout topBar = createTopBar();
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout importCard = card();
        importCard.setPadding(dp(16), dp(13), dp(16), dp(15));
        TextView kicker = textView("LISTEN", 11, Color.rgb(119, 106, 176));
        kicker.setTypeface(null, Typeface.BOLD);
        importCard.addView(kicker, wrapParams());
        TextView hint = textView(
                "导入黑白谱、结构化 TXT 或已有彩谱，选择一首后开始试听",
                13,
                TEXT_SECONDARY
        );
        hint.setPadding(0, dp(4), 0, dp(10));
        importCard.addView(hint, wrapParams());
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
        LinearLayout.LayoutParams importParams = wrapParams();
        importParams.setMargins(dp(16), 0, dp(16), dp(8));
        root.addView(importCard, importParams);

        songList = new ListView(this);
        songList.setDivider(null);
        songList.setDividerHeight(0);
        songList.setBackgroundColor(PAGE_BACKGROUND);
        songList.setPadding(dp(16), dp(3), dp(16), dp(8));
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
        statusText = textView("等待导入谱面", 12, Color.rgb(67, 53, 108));
        statusText.setMaxLines(2);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        bottomBar.addView(statusText, wrapParams());
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
        SystemBarInsets.install(this, root, topBar, bottomBar);
    }

    private LinearLayout createTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(7), dp(16), dp(9));
        Button back = controlButton("‹");
        back.setTextSize(28);
        back.setContentDescription("返回");
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(view -> finish());

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(9), 0, 0, 0);
        TextView title = textView("Aurora Keys", 23, TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        textColumn.addView(title, wrapParams());
        TextView subtitle = textView("彩谱试听器 · 按时间播放，或逐层放慢练习", 12, TEXT_SECONDARY);
        subtitle.setPadding(0, dp(2), 0, 0);
        textColumn.addView(subtitle, wrapParams());
        bar.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        return bar;
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

    private void loadFiles(List<Uri> uris) {
        setImporting(true, "正在读取和检查文件……");
        worker.execute(() -> {
            SafDocumentStore store = new SafDocumentStore(getContentResolver());
            List<PlayableItem> loaded = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Uri uri : uris) {
                if (uri == null || !seen.add(uri.toString())) {
                    continue;
                }
                String name = store.displayName(uri);
                loaded.addAll(readOne(store, uri, name));
            }
            postLoaded(loaded, "文件");
        });
    }

    private void loadFolder(Uri tree) {
        setImporting(true, "正在递归扫描文件夹……");
        worker.execute(() -> {
            try {
                SafDocumentStore store = new SafDocumentStore(getContentResolver());
                List<PlayableItem> loaded = new ArrayList<>();
                for (SafDocumentStore.Entry entry : store.findInputFiles(tree)) {
                    loaded.addAll(readOne(store, entry.uri, entry.name));
                }
                postLoaded(loaded, "文件夹");
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setImporting(false, "扫描失败：" + messageOf(exception));
                    items.clear();
                    adapter.notifyDataSetChanged();
                    importSummary.setText("未能读取输入文件夹");
                });
            }
        });
    }

    private List<PlayableItem> readOne(
            SafDocumentStore store,
            Uri uri,
            String sourceName
    ) {
        try {
            List<PlayableItem> result = new ArrayList<>();
            for (ScoreImportReader.ImportedScore imported
                    : ScoreImportReader.read(store.readBytes(uri), sourceName)) {
                result.add(new PlayableItem(uri, imported));
            }
            return result;
        } catch (Exception exception) {
            return Collections.singletonList(new PlayableItem(
                    uri,
                    ScoreImportReader.read(null, sourceName).get(0)
            ).withError(messageOf(exception)));
        }
    }

    private void postLoaded(List<PlayableItem> loaded, String sourceKind) {
        mainHandler.post(() -> {
            setImporting(false, "");
            items.clear();
            if (loaded != null) {
                items.addAll(loaded);
            }
            adapter.notifyDataSetChanged();
            int playable = 0;
            int invalid = 0;
            for (PlayableItem item : items) {
                if (item.isPlayable()) {
                    playable++;
                } else {
                    invalid++;
                }
            }
            importSummary.setText("已导入 " + items.size() + " 首 · 可试听 " + playable
                    + " · 错误 " + invalid + "（" + sourceKind + "模式）");
            if (items.isEmpty()) {
                statusText.setText("没有找到 .json 或 .txt 文件");
            } else if (invalid > 0) {
                statusText.setText("无效文件已保留并显示原因，其余歌曲可以开始试听");
            } else {
                statusText.setText("选择一首歌曲开始试听");
            }
        });
    }

    private void openPlayer(PlayableItem item) {
        if (item == null || !item.isPlayable()) {
            showToast("此项目没有可试听的有效谱面");
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_URI, item.sourceUri.toString());
        intent.putExtra(PlayerActivity.EXTRA_SOURCE_NAME, item.imported.sourceName);
        intent.putExtra(PlayerActivity.EXTRA_SONG_INDEX, item.imported.songIndex);
        intent.putExtra(
                PlayerActivity.EXTRA_SOURCE_KIND,
                item.imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW
                        ? PlayerActivity.SOURCE_KIND_COLOR
                        : PlayerActivity.SOURCE_KIND_BLACK
        );
        startActivity(intent);
    }

    private void setImporting(boolean importing, String message) {
        chooseFilesButton.setEnabled(!importing);
        chooseInputFolderButton.setEnabled(!importing);
        if (message != null && !message.isEmpty()) {
            statusText.setText(message);
        }
    }

    private void persistGrant(Intent data, List<Uri> uris) {
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        for (Uri uri : uris) {
            SafDocumentStore.takePersistablePermission(getContentResolver(), uri, flags);
        }
    }

    private List<Uri> selectedUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                Uri uri = data.getClipData().getItemAt(index).getUri();
                if (uri != null) {
                    result.add(uri);
                }
            }
        }
        if (data.getData() != null && result.isEmpty()) {
            result.add(data.getData());
        }
        return result;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(16));
        card.setBackground(roundRect(SURFACE, 20, 0, 0));
        return card;
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
            PlayableItem item = items.get(position);
            LinearLayout row = new LinearLayout(PlayerHomeActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(15), dp(13), dp(12), dp(12));
            row.setBackground(roundRect(SURFACE, 18, 0, 0));
            row.setElevation(dp(1));

            LinearLayout titleRow = new LinearLayout(PlayerHomeActivity.this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = textView(item.badge(), 11, Color.WHITE);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(9), dp(4), dp(9), dp(4));
            badge.setBackground(roundRect(item.isPlayable() ? item.badgeColor() : ERROR, 9, 0, 0));
            titleRow.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            TextView title = textView(item.title(), 16, TEXT_PRIMARY);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0, dp(42), 1f
            );
            titleParams.setMargins(dp(9), 0, dp(7), 0);
            titleRow.addView(title, titleParams);
            Button listen = item.isPlayable()
                    ? primaryButton("开始试听")
                    : secondaryButton("不可试听");
            listen.setEnabled(item.isPlayable());
            listen.setContentDescription(item.isPlayable() ? "开始试听" : "无效谱面");
            titleRow.addView(listen, new LinearLayout.LayoutParams(dp(100), dp(48)));
            listen.setOnClickListener(view -> openPlayer(item));
            row.addView(titleRow, wrapParams());

            TextView source = textView(item.sourceLabel(), 12, TEXT_SECONDARY);
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            source.setPadding(0, dp(5), 0, 0);
            row.addView(source, wrapParams());
            if (!item.isPlayable()) {
                TextView error = textView("错误：" + item.error(), 12, ERROR);
                error.setMaxLines(3);
                error.setEllipsize(TextUtils.TruncateAt.END);
                error.setPadding(0, dp(5), 0, 0);
                row.addView(error, wrapParams());
            }
            AbsListView.LayoutParams rowParams = new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            row.setLayoutParams(rowParams);
            return row;
        }
    }

    private static final class PlayableItem {
        final Uri sourceUri;
        final ScoreImportReader.ImportedScore imported;
        private String customError;

        PlayableItem(Uri sourceUri, ScoreImportReader.ImportedScore imported) {
            this.sourceUri = sourceUri;
            this.imported = imported;
        }

        PlayableItem withError(String error) {
            customError = error;
            return this;
        }

        boolean isPlayable() {
            return imported != null && (imported.kind == ScoreImportReader.Kind.BLACK
                    ? imported.blackDocument != null
                    : imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW
                    && imported.colorDocument != null);
        }

        String badge() {
            if (imported == null || imported.kind == ScoreImportReader.Kind.INVALID) {
                return "无效";
            }
            return imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW ? "彩谱试听" : "黑白谱";
        }

        int badgeColor() {
            return imported != null && imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW
                    ? COLOR_BADGE : BLACK_BADGE;
        }

        String title() {
            if (imported == null) {
                return "无法识别的谱面";
            }
            if (imported.kind == ScoreImportReader.Kind.COLOR_PREVIEW
                    && imported.colorDocument != null) {
                return imported.colorDocument.title;
            }
            if (imported.blackDocument != null) {
                return imported.blackDocument.title;
            }
            return "无法识别的谱面";
        }

        String sourceLabel() {
            if (imported == null) {
                return "";
            }
            if (imported.songCount > 1) {
                return imported.sourceName + " · 第 " + (imported.songIndex + 1)
                        + "/" + imported.songCount + " 首";
            }
            return imported.sourceName;
        }

        String error() {
            if (customError != null && !customError.trim().isEmpty()) {
                return customError;
            }
            return imported == null || imported.error.trim().isEmpty()
                    ? "未知格式" : imported.error;
        }
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

    private Button secondaryButton(String label) {
        Button button = controlButton(label);
        button.setTextColor(TEXT_PRIMARY);
        button.setBackground(roundRect(Color.rgb(242, 240, 250), 14, 0, 0));
        return button;
    }

    private Button primaryButton(String label) {
        Button button = controlButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(PRIMARY, 14, 0, 0));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(0, 0, dp(6), 0);
        return params;
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
}
