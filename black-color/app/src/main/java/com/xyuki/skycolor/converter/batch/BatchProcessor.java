package com.xyuki.skycolor.converter.batch;

import android.content.ContentResolver;
import android.net.Uri;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;
import com.xyuki.skycolor.converter.core.ColorScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreWriter;
import com.xyuki.skycolor.converter.render.ColorPageRenderer;
import com.xyuki.skycolor.converter.storage.SafDocumentStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs independent conversion jobs and keeps failures isolated from the rest of the batch. */
public final class BatchProcessor {
    private static final CancellationToken NEVER_CANCELLED = () -> false;

    private BatchProcessor() {
    }

    public static BatchSummary run(
            ContentResolver resolver,
            List<InputItem> items,
            Uri outputTree,
            Map<String, String> titleOverrides,
            ProgressListener listener,
            CancellationToken cancellation
    ) throws IOException {
        if (resolver == null) {
            throw new IOException("存储服务不可用");
        }
        if (outputTree == null) {
            throw new IOException("请选择输出文件夹");
        }
        List<InputItem> safeItems = items == null ? Collections.emptyList() : items;
        ProgressListener safeListener = listener == null ? new ProgressListener() {
        } : listener;
        CancellationToken safeCancellation = cancellation == null ? NEVER_CANCELLED : cancellation;
        SafDocumentStore store = new SafDocumentStore(resolver);
        Uri outputRoot = store.rootDocumentUri(outputTree);
        Set<String> usedFolderNames = new HashSet<>();
        for (SafDocumentStore.Entry entry : store.listChildren(outputTree, outputRoot)) {
            usedFolderNames.add(entry.name);
        }
        List<FileOutcome> outcomes = new ArrayList<>();
        safeListener.onStarted(safeItems.size());
        int completed = 0;
        for (InputItem item : safeItems) {
            if (safeCancellation.isCancelled()) {
                FileOutcome outcome = FileOutcome.skipped(item, "用户已取消，未处理");
                outcomes.add(outcome);
                completed++;
                safeListener.onProgress(completed, safeItems.size(), outcome.message);
                continue;
            }
            FileOutcome outcome;
            try {
                outcome = processOne(
                        store,
                        outputTree,
                        outputRoot,
                        usedFolderNames,
                        item,
                        titleOverrides
                );
            } catch (Exception exception) {
                String message = messageOf(exception);
                outcome = FileOutcome.failed(item, message);
                safeListener.onLog("失败：" + item.sourceName + "：" + message);
            }
            outcomes.add(outcome);
            completed++;
            safeListener.onProgress(completed, safeItems.size(), outcome.message);
        }
        BatchSummary summary = new BatchSummary(outputTree, outcomes);
        safeListener.onFinished(summary);
        return summary;
    }

    private static FileOutcome processOne(
            SafDocumentStore store,
            Uri outputTree,
            Uri outputRoot,
            Set<String> usedFolderNames,
            InputItem item,
            Map<String, String> titleOverrides
    ) throws IOException {
        if (item == null) {
            return FileOutcome.failed(null, "空任务");
        }
        if (item.error != null && !item.error.trim().isEmpty()) {
            return FileOutcome.failed(item, item.error);
        }
        if (item.isPreviewOnly()) {
            return FileOutcome.skipped(item, "彩谱仅用于试听，未参与生成");
        }
        if (item.document == null) {
            return FileOutcome.failed(item, "没有可转换的黑白谱数据");
        }
        String override = titleOverrides == null ? null : titleOverrides.get(item.id());
        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(
                item.document,
                override
        );
        String baseName = outputBaseName(item.sourceName, item.songIndex, item.songCount);
        String folderName = uniqueFolderName(baseName, usedFolderNames);
        Uri outputDirectory = store.ensureDirectory(outputTree, outputRoot, folderName);
        List<String> pageNames = new ArrayList<>();
        int pageCount = Math.max(
                1,
                (conversion.images.size() + ColorPageRenderer.MOBILE_PAGE_SIZE - 1)
                        / ColorPageRenderer.MOBILE_PAGE_SIZE
        );
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            int from = pageIndex * ColorPageRenderer.MOBILE_PAGE_SIZE;
            int to = Math.min(
                    conversion.images.size(),
                    from + ColorPageRenderer.MOBILE_PAGE_SIZE
            );
            List<ColorScoreConverter.ColorImage> pageImages = conversion.images.subList(from, to);
            byte[] bodyPng = ColorPageRenderer.renderMobilePage(
                    pageImages,
                    conversion.title,
                    pageIndex + 1,
                    pageCount
            );
            if (pageIndex == 0) {
                String coverName = baseName + ".color-mobile-000.png";
                store.writeBytes(outputTree, outputDirectory, coverName, "image/png",
                        ColorPageRenderer.renderCoverPage(bodyPng, conversion.title));
                pageNames.add(coverName);
            }
            String pageName = String.format(
                    Locale.ROOT,
                    "%s.color-mobile-%03d.png",
                    baseName,
                    pageIndex + 1
            );
            store.writeBytes(outputTree, outputDirectory, pageName, "image/png", bodyPng);
            pageNames.add(pageName);
        }
        String jsonName = baseName + ".color.json";
        store.writeUtf8(
                outputTree,
                outputDirectory,
                jsonName,
                ColorScoreWriter.toJson(conversion, pageNames, jsonName)
        );
        return FileOutcome.success(item, "已生成 " + folderName, folderName);
    }

    public static String safeName(String value) {
        String source = value == null ? "" : value.trim();
        StringBuilder result = new StringBuilder();
        boolean separatorAdded = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            boolean invalid = character == '<' || character == '>' || character == ':'
                    || character == '"' || character == '/' || character == '\\'
                    || character == '|' || character == '?' || character == '*';
            if (invalid) {
                if (!separatorAdded) {
                    result.append('-');
                    separatorAdded = true;
                }
            } else {
                result.append(character);
                separatorAdded = false;
            }
        }
        String sanitized = result.toString().trim();
        while (sanitized.endsWith(".") || sanitized.endsWith(" ")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        if (sanitized.isEmpty()) {
            return "未命名歌曲";
        }
        String upper = sanitized.toUpperCase(Locale.ROOT);
        if (Arrays.asList("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
                "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3",
                "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(upper)) {
            sanitized = sanitized + "_";
        }
        return sanitized;
    }

    public static String outputBaseName(String sourceName, int songIndex, int songCount) {
        String stem = stem(sourceName);
        if (songCount > 1) {
            stem = stem + String.format(Locale.ROOT, " - %02d", songIndex + 1);
        }
        return safeName(stem);
    }

    public static String uniqueFolderName(String baseName, Set<String> usedNames) {
        if (usedNames == null) {
            throw new IllegalArgumentException("已用名称集合不能为空");
        }
        String base = safeName(baseName);
        String candidate = base;
        int suffix = 2;
        while (containsIgnoreCase(usedNames, candidate)) {
            candidate = base + " (" + suffix++ + ")";
        }
        usedNames.add(candidate);
        return candidate;
    }

    private static boolean containsIgnoreCase(Set<String> names, String candidate) {
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String stem(String sourceName) {
        String value = sourceName == null ? "" : sourceName.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        return value.isEmpty() ? "未命名歌曲" : value;
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    public interface CancellationToken {
        boolean isCancelled();
    }

    public interface ProgressListener {
        default void onStarted(int total) {
        }

        default void onProgress(int completed, int total, String message) {
        }

        default void onLog(String message) {
        }

        default void onFinished(BatchSummary summary) {
        }
    }

    public static final class InputItem {
        public final Uri sourceUri;
        public final String sourceName;
        public final int songIndex;
        public final int songCount;
        public final BlackScoreReader.ScoreDocument document;
        public final ColorScoreReader.ColorDocument colorDocument;
        public final String error;
        public final InputKind kind;

        public InputItem(
                Uri sourceUri,
                String sourceName,
                int songIndex,
                int songCount,
                BlackScoreReader.ScoreDocument document,
                String error
        ) {
            this(
                    sourceUri,
                    sourceName,
                    songIndex,
                    songCount,
                    document,
                    null,
                    error,
                    document == null ? InputKind.INVALID : InputKind.BLACK
            );
        }

        private InputItem(
                Uri sourceUri,
                String sourceName,
                int songIndex,
                int songCount,
                BlackScoreReader.ScoreDocument document,
                ColorScoreReader.ColorDocument colorDocument,
                String error,
                InputKind kind
        ) {
            this.sourceUri = sourceUri;
            this.sourceName = sourceName == null || sourceName.trim().isEmpty()
                    ? "输入谱面.json" : sourceName;
            this.songIndex = songIndex;
            this.songCount = Math.max(1, songCount);
            this.document = document;
            this.colorDocument = colorDocument;
            this.error = error == null ? "" : error;
            this.kind = kind;
        }

        public static InputItem success(
                Uri sourceUri,
                String sourceName,
                int songIndex,
                int songCount,
                BlackScoreReader.ScoreDocument document
        ) {
            return new InputItem(sourceUri, sourceName, songIndex, songCount, document, "");
        }

        public static InputItem failure(Uri sourceUri, String sourceName, String error) {
            return new InputItem(sourceUri, sourceName, 0, 1, null, error);
        }

        public static InputItem previewOnly(
                Uri sourceUri,
                String sourceName,
                ColorScoreReader.ColorDocument colorDocument
        ) {
            if (colorDocument == null) {
                throw new IllegalArgumentException("彩谱文档不能为空");
            }
            return new InputItem(
                    sourceUri,
                    sourceName,
                    0,
                    1,
                    null,
                    colorDocument,
                    "",
                    InputKind.COLOR_PREVIEW
            );
        }

        public String id() {
            String uriPart = sourceUri == null ? sourceName : sourceUri.toString();
            return uriPart + "#" + songIndex;
        }

        public String defaultTitle() {
            if (document != null) {
                return document.title;
            }
            return colorDocument == null ? "" : colorDocument.title;
        }

        public boolean isPreviewOnly() {
            return kind == InputKind.COLOR_PREVIEW;
        }

        public boolean isGeneratable() {
            return kind == InputKind.BLACK && document != null && error.trim().isEmpty();
        }
    }

    public enum InputKind {
        BLACK,
        COLOR_PREVIEW,
        INVALID
    }

    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    public static final class FileOutcome {
        public final InputItem item;
        public final Status status;
        public final String message;
        public final String outputDirectoryName;

        private FileOutcome(
                InputItem item,
                Status status,
                String message,
                String outputDirectoryName
        ) {
            this.item = item;
            this.status = status;
            this.message = message;
            this.outputDirectoryName = outputDirectoryName;
        }

        private static FileOutcome success(InputItem item, String message, String outputName) {
            return new FileOutcome(item, Status.SUCCESS, message, outputName);
        }

        private static FileOutcome failed(InputItem item, String message) {
            return new FileOutcome(item, Status.FAILED, message, "");
        }

        private static FileOutcome skipped(InputItem item, String message) {
            return new FileOutcome(item, Status.SKIPPED, message, "");
        }
    }

    public static final class BatchSummary {
        public final Uri outputTree;
        public final List<FileOutcome> outcomes;
        public final int successCount;
        public final int failedCount;
        public final int skippedCount;

        private BatchSummary(Uri outputTree, List<FileOutcome> outcomes) {
            this.outputTree = outputTree;
            this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
            int success = 0;
            int failed = 0;
            int skipped = 0;
            for (FileOutcome outcome : outcomes) {
                if (outcome.status == Status.SUCCESS) {
                    success++;
                } else if (outcome.status == Status.FAILED) {
                    failed++;
                } else {
                    skipped++;
                }
            }
            this.successCount = success;
            this.failedCount = failed;
            this.skippedCount = skipped;
        }
    }
}
