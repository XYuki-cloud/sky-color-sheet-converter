package com.xyuki.skyconverter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.xyuki.skyconverter.batch.BatchConverter;
import com.xyuki.skyconverter.core.SkyConverter;
import com.xyuki.skyconverter.storage.TreeDocumentStore;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Folder-oriented Android front end for the dependency-free MIDI converter. */
public class MainActivity extends Activity {
    private static final int REQUEST_INPUT_TREE = 1001;
    private static final int REQUEST_OUTPUT_TREE = 1002;
    private static final String PREFS = "converter_preferences";
    private static final String PREF_INPUT = "input_tree";
    private static final String PREF_OUTPUT = "output_tree";
    private static final String PREF_KEY = "key";
    private static final String PREF_SUBDIVISIONS = "subdivisions";
    private static final String PREF_POLICY = "chromatic_policy";
    private static final String PREF_SHIFT = "shift";

    private static final String[] KEYS = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };
    private static final String[] SUBDIVISIONS = {"1", "2", "4", "8"};
    private static final String[] POLICY_LABELS = {
            "丢弃半音（drop）",
            "就近映射（nearest）",
            "遇到半音停止（error）"
    };
    private static final SkyConverter.ChromaticPolicy[] POLICIES = {
            SkyConverter.ChromaticPolicy.DROP,
            SkyConverter.ChromaticPolicy.NEAREST,
            SkyConverter.ChromaticPolicy.ERROR
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancellation = new AtomicBoolean(false);
    private SharedPreferences preferences;
    private Uri inputTree;
    private Uri outputTree;
    private boolean running;

    private TextView inputFolder;
    private TextView outputFolder;
    private TextView status;
    private TextView log;
    private ProgressBar progress;
    private Button inputButton;
    private Button outputButton;
    private Button startButton;
    private Button cancelButton;
    private Spinner keySpinner;
    private Spinner subdivisionsSpinner;
    private Spinner policySpinner;
    private EditText shiftInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildScreen();
        restoreConfiguration();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        scrollView.addView(root);

        TextView title = text(getString(R.string.app_name), 24f);
        title.setTextColor(0xFF202124);
        root.addView(title, matchWrap());

        TextView description = text(
                "批量读取 MIDI，生成黑白谱、黑红蓝彩谱 JSON、音符侧车和手机 PNG。\n"
                        + "Android 使用系统文件夹授权，会递归扫描所选输入文件夹。",
                14f
        );
        description.setTextColor(0xFF5F6368);
        description.setPadding(0, dp(8), 0, dp(14));
        root.addView(description, matchWrap());

        root.addView(sectionLabel("输入文件夹"), matchWrap());
        inputFolder = text("未选择", 14f);
        inputFolder.setTextColor(0xFF3C4043);
        root.addView(inputFolder, matchWrap());
        inputButton = button("选择输入文件夹");
        inputButton.setOnClickListener(view -> chooseTree(REQUEST_INPUT_TREE));
        root.addView(inputButton, matchWrap());

        root.addView(sectionLabel("输出文件夹"), matchWrap());
        outputFolder = text("未选择", 14f);
        outputFolder.setTextColor(0xFF3C4043);
        root.addView(outputFolder, matchWrap());
        outputButton = button("选择输出文件夹");
        outputButton.setOnClickListener(view -> chooseTree(REQUEST_OUTPUT_TREE));
        root.addView(outputButton, matchWrap());

        TextView optionTitle = sectionLabel("转换参数");
        optionTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(optionTitle, matchWrap());

        root.addView(labelledSpinner("调性（大调）", KEYS, 0, spinnerHolder -> keySpinner = spinnerHolder), matchWrap());
        root.addView(labelledSpinner("每拍细分", SUBDIVISIONS, 2, spinnerHolder -> subdivisionsSpinner = spinnerHolder), matchWrap());
        root.addView(labelledSpinner("半音处理", POLICY_LABELS, 0, spinnerHolder -> policySpinner = spinnerHolder), matchWrap());

        TextView shiftLabel = text("音阶位移（留空自动以最低音对齐）", 14f);
        shiftLabel.setTextColor(0xFF3C4043);
        shiftLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(shiftLabel, matchWrap());
        shiftInput = new EditText(this);
        shiftInput.setSingleLine(true);
        shiftInput.setHint("自动");
        shiftInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        root.addView(shiftInput, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1);
        progress.setProgress(0);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(18);
        root.addView(progress, progressParams);

        status = text("等待选择文件夹", 14f);
        status.setTextColor(0xFF3C4043);
        status.setPadding(0, dp(8), 0, dp(6));
        root.addView(status, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        startButton = button("开始批量转换");
        startButton.setOnClickListener(view -> startConversion());
        cancelButton = button("取消");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(view -> cancellation.set(true));
        actions.addView(startButton, weightWrap(1f));
        LinearLayout.LayoutParams cancelParams = weightWrap(1f);
        cancelParams.leftMargin = dp(8);
        actions.addView(cancelButton, cancelParams);
        root.addView(actions, matchWrap());

        TextView logLabel = sectionLabel("运行日志");
        logLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(logLabel, matchWrap());
        log = text("", 13f);
        log.setTextColor(0xFF202124);
        log.setGravity(Gravity.TOP | Gravity.START);
        log.setMinHeight(dp(160));
        log.setBackgroundColor(0xFFF1F3F4);
        log.setPadding(dp(10), dp(8), dp(10), dp(8));
        log.setTextIsSelectable(true);
        root.addView(log, matchWrap());

        setContentView(scrollView);
    }

    private LinearLayout labelledSpinner(
            String label,
            String[] values,
            int defaultIndex,
            SpinnerReceiver receiver
    ) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 14f);
        labelView.setTextColor(0xFF3C4043);
        labelView.setPadding(0, dp(10), 0, dp(4));
        holder.addView(labelView, matchWrap());
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                Arrays.asList(values)
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(defaultIndex);
        receiver.accept(spinner);
        holder.addView(spinner, matchWrap());
        return holder;
    }

    private void chooseTree(int requestCode) {
        if (running) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri selected = data.getData();
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, takeFlags);
        } catch (SecurityException error) {
            appendLog("提示：系统未允许持久化该文件夹授权，重启应用后可能需要重新选择。\n");
        }
        if (requestCode == REQUEST_INPUT_TREE) {
            inputTree = selected;
            inputFolder.setText(folderLabel(selected));
            preferences.edit().putString(PREF_INPUT, selected.toString()).apply();
        } else if (requestCode == REQUEST_OUTPUT_TREE) {
            outputTree = selected;
            outputFolder.setText(folderLabel(selected));
            preferences.edit().putString(PREF_OUTPUT, selected.toString()).apply();
        }
        status.setText("文件夹已选择，可以开始转换");
    }

    private void restoreConfiguration() {
        inputTree = restoreTree(PREF_INPUT, false);
        outputTree = restoreTree(PREF_OUTPUT, true);
        if (inputTree != null) {
            inputFolder.setText(folderLabel(inputTree));
        }
        if (outputTree != null) {
            outputFolder.setText(folderLabel(outputTree));
        }

        selectValue(keySpinner, preferences.getString(PREF_KEY, "C"));
        selectValue(subdivisionsSpinner, preferences.getString(PREF_SUBDIVISIONS, "4"));
        String savedPolicy = preferences.getString(PREF_POLICY, "drop");
        int policyIndex = 0;
        for (int index = 0; index < POLICIES.length; index++) {
            if (POLICIES[index].name().toLowerCase(Locale.ROOT).equals(savedPolicy)) {
                policyIndex = index;
                break;
            }
        }
        policySpinner.setSelection(policyIndex);
        shiftInput.setText(preferences.getString(PREF_SHIFT, ""));
        if (inputTree == null || outputTree == null) {
            status.setText("请选择输入和输出文件夹");
        }
    }

    private Uri restoreTree(String preferenceKey, boolean requireWrite) {
        String value = preferences.getString(preferenceKey, "");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Uri uri = Uri.parse(value);
        if (!hasPersistedGrant(uri, requireWrite)) {
            appendLog("已保存的文件夹授权失效，请重新选择：" + uri + "\n");
            return null;
        }
        return uri;
    }

    private void startConversion() {
        if (running) {
            return;
        }
        if (inputTree == null || !hasPersistedGrant(inputTree, false)) {
            status.setText("请先选择仍可读取的输入文件夹");
            return;
        }
        if (outputTree == null || !hasPersistedGrant(outputTree, true)) {
            status.setText("请先选择仍可写入的输出文件夹");
            return;
        }
        if (inputTree.equals(outputTree)) {
            status.setText("输入和输出文件夹不能相同");
            return;
        }

        final SkyConverter.Options options;
        try {
            Integer shift = parseShift();
            String key = (String) keySpinner.getSelectedItem();
            int subdivisions = Integer.parseInt((String) subdivisionsSpinner.getSelectedItem());
            int policyIndex = policySpinner.getSelectedItemPosition();
            SkyConverter.ChromaticPolicy policy = POLICIES[policyIndex];
            options = new SkyConverter.Options(key, subdivisions, shift, policy, "");
            saveOptions(key, subdivisions, policy, shift);
        } catch (RuntimeException error) {
            status.setText("参数错误：" + readableMessage(error));
            return;
        }

        running = true;
        cancellation.set(false);
        setControlsEnabled(false);
        progress.setIndeterminate(false);
        progress.setMax(1);
        progress.setProgress(0);
        status.setText("正在扫描输入文件夹…");
        log.setText("");

        final Uri selectedInput = inputTree;
        final Uri selectedOutput = outputTree;
        executor.submit(() -> {
            try {
                BatchConverter.run(
                        getContentResolver(),
                        selectedInput,
                        selectedOutput,
                        options,
                        new BatchConverter.ProgressListener() {
                            @Override
                            public void onStarted(int total) {
                                runOnUiThread(() -> {
                                    progress.setMax(Math.max(1, total));
                                    progress.setProgress(0);
                                    status.setText("找到 " + total + " 个 MIDI 文件");
                                });
                            }

                            @Override
                            public void onFileProgress(int index, int total, String name) {
                                runOnUiThread(() -> {
                                    progress.setProgress(index);
                                    status.setText("处理 " + index + "/" + total + "：" + name);
                                });
                            }

                            @Override
                            public void onLog(String message) {
                                runOnUiThread(() -> appendLog(message + "\n"));
                            }

                            @Override
                            public void onFinished(BatchConverter.BatchSummary summary) {
                                runOnUiThread(() -> finishConversion(summary));
                            }
                        },
                        cancellation::get
                );
            } catch (Exception error) {
                runOnUiThread(() -> finishWithError(error));
            }
        });
    }

    private void finishConversion(BatchConverter.BatchSummary summary) {
        if (!running) {
            return;
        }
        running = false;
        setControlsEnabled(true);
        progress.setProgress(progress.getMax());
        status.setText("批量转换完成：" + summary);
        appendLog("\n" + summary + "\n");
        for (BatchConverter.FileOutcome outcome : summary.outcomes) {
            appendLog(outcome.status + "  " + outcome.name + "  " + outcome.message + "\n");
        }
    }

    private void finishWithError(Exception error) {
        if (!running) {
            return;
        }
        running = false;
        setControlsEnabled(true);
        status.setText("批量转换失败：" + readableMessage(error));
        appendLog("批量转换失败：" + readableMessage(error) + "\n");
    }

    private void setControlsEnabled(boolean enabled) {
        inputButton.setEnabled(enabled);
        outputButton.setEnabled(enabled);
        keySpinner.setEnabled(enabled);
        subdivisionsSpinner.setEnabled(enabled);
        policySpinner.setEnabled(enabled);
        shiftInput.setEnabled(enabled);
        startButton.setEnabled(enabled);
        cancelButton.setEnabled(!enabled);
    }

    private Integer parseShift() {
        String value = shiftInput.getText() == null ? "" : shiftInput.getText().toString().trim();
        return value.isEmpty() ? null : Integer.valueOf(value);
    }

    private void saveOptions(String key, int subdivisions, SkyConverter.ChromaticPolicy policy, Integer shift) {
        preferences.edit()
                .putString(PREF_KEY, key)
                .putString(PREF_SUBDIVISIONS, Integer.toString(subdivisions))
                .putString(PREF_POLICY, policy.name().toLowerCase(Locale.ROOT))
                .putString(PREF_SHIFT, shift == null ? "" : Integer.toString(shift))
                .apply();
    }

    private boolean hasPersistedGrant(Uri uri, boolean requireWrite) {
        List<UriPermission> grants = getContentResolver().getPersistedUriPermissions();
        for (UriPermission grant : grants) {
            if (uri.equals(grant.getUri())
                    && grant.isReadPermission()
                    && (!requireWrite || grant.isWritePermission())) {
                return true;
            }
        }
        return false;
    }

    private String folderLabel(Uri uri) {
        try {
            return TreeDocumentStore.displayName(getContentResolver(), uri);
        } catch (RuntimeException error) {
            return uri.toString();
        }
    }

    private void selectValue(Spinner spinner, String value) {
        if (spinner == null || value == null) {
            return;
        }
        for (int index = 0; index < spinner.getCount(); index++) {
            if (value.equals(spinner.getItemAtPosition(index))) {
                spinner.setSelection(index);
                return;
            }
        }
    }

    private void appendLog(String message) {
        if (log != null) {
            log.append(message);
        }
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 16f);
        view.setTextColor(0xFF5F6368);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    @Override
    protected void onDestroy() {
        cancellation.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface SpinnerReceiver {
        void accept(Spinner spinner);
    }
}
