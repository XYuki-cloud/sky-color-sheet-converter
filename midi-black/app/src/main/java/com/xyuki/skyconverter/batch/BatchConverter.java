package com.xyuki.skyconverter.batch;

import android.content.ContentResolver;
import android.net.Uri;

import com.xyuki.skyconverter.core.JsonWriter;
import com.xyuki.skyconverter.core.MidiFileReader;
import com.xyuki.skyconverter.core.SkyConverter;
import com.xyuki.skyconverter.render.SkyPageRenderer;
import com.xyuki.skyconverter.storage.TreeDocumentStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Coordinates recursive MIDI discovery, conversion, page rendering, and SAF export. */
public final class BatchConverter {
    private BatchConverter() {
    }

    public interface ProgressListener {
        default void onStarted(int total) {
        }

        default void onFileProgress(int index, int total, String name) {
        }

        default void onLog(String message) {
        }

        default void onFinished(BatchSummary summary) {
        }
    }

    public interface CancellationToken {
        boolean isCancelled();
    }

    public static final CancellationToken NEVER_CANCELLED = () -> false;

    public enum Status {
        SUCCESS,
        FAILURE,
        SKIPPED
    }

    public static final class FileOutcome {
        public final String name;
        public final Status status;
        public final String message;

        private FileOutcome(String name, Status status, String message) {
            this.name = name == null ? "" : name;
            this.status = status;
            this.message = message == null ? "" : message;
        }

        public static FileOutcome success(String name) {
            return success(name, "转换完成");
        }

        public static FileOutcome success(String name, String message) {
            return new FileOutcome(name, Status.SUCCESS, message);
        }

        public static FileOutcome failure(String name, String message) {
            return new FileOutcome(name, Status.FAILURE, message);
        }

        public static FileOutcome skipped(String name) {
            return new FileOutcome(name, Status.SKIPPED, "已取消，未处理");
        }
    }

    public static final class BatchSummary {
        public final List<FileOutcome> outcomes;
        public final int successCount;
        public final int failureCount;
        public final int skippedCount;

        private BatchSummary(
                List<FileOutcome> outcomes,
                int successCount,
                int failureCount,
                int skippedCount
        ) {
            this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.skippedCount = skippedCount;
        }

        @Override
        public String toString() {
            return "成功 " + successCount + "，失败 " + failureCount + "，跳过 " + skippedCount;
        }
    }

    /** Sanitizes a source stem for both Android document providers and Windows users. */
    public static String safeName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
        name = name.replaceAll("_+$", "");
        name = trimTrailingDotsAndSpaces(name);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "未命名";
        }
        if (isReservedWindowsName(name)) {
            return "_" + name;
        }
        return name;
    }

    /** Returns a stable, case-insensitive unique folder name and records it in {@code used}. */
    public static String uniqueFolderName(String value, Set<String> used) {
        if (used == null) {
            throw new IllegalArgumentException("used 不能为空");
        }
        String base = safeName(value);
        String candidate = base;
        int suffix = 2;
        while (containsIgnoreCase(used, candidate)) {
            candidate = base + " (" + suffix++ + ")";
        }
        used.add(candidate);
        return candidate;
    }

    public static BatchSummary summarize(List<FileOutcome> outcomes) {
        List<FileOutcome> safeOutcomes = outcomes == null
                ? Collections.emptyList()
                : outcomes;
        int success = 0;
        int failure = 0;
        int skipped = 0;
        for (FileOutcome outcome : safeOutcomes) {
            if (outcome == null || outcome.status == null) {
                continue;
            }
            switch (outcome.status) {
                case SUCCESS -> success++;
                case FAILURE -> failure++;
                case SKIPPED -> skipped++;
            }
        }
        return new BatchSummary(safeOutcomes, success, failure, skipped);
    }

    /**
     * Converts all MIDI documents below the selected input tree into one output
     * folder per source file. A failure is isolated to that file.
     */
    public static BatchSummary run(
            ContentResolver resolver,
            Uri inputTree,
            Uri outputTree,
            SkyConverter.Options options,
            ProgressListener listener,
            CancellationToken cancellation
    ) throws IOException {
        if (resolver == null || inputTree == null || outputTree == null) {
            throw new IllegalArgumentException("文件夹转换参数不能为空");
        }
        if (options == null) {
            throw new IllegalArgumentException("转换选项不能为空");
        }
        ProgressListener progress = listener == null ? new ProgressListener() {
        } : listener;
        CancellationToken token = cancellation == null ? NEVER_CANCELLED : cancellation;
        List<TreeDocumentStore.Entry> inputFiles = TreeDocumentStore.findMidiFiles(resolver, inputTree);
        progress.onStarted(inputFiles.size());

        Uri outputRoot = TreeDocumentStore.rootDocumentUri(outputTree);
        Set<String> usedFolderNames = new LinkedHashSet<>();
        List<FileOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < inputFiles.size(); index++) {
            TreeDocumentStore.Entry input = inputFiles.get(index);
            progress.onFileProgress(index + 1, inputFiles.size(), input.name);
            if (token.isCancelled()) {
                addSkipped(outcomes, inputFiles, index);
                break;
            }
            try {
                progress.onLog("读取：" + input.name);
                byte[] midiBytes = TreeDocumentStore.readBytes(resolver, input.uri);
                MidiFileReader.Result midi = MidiFileReader.read(midiBytes, input.name);
                SkyConverter.Conversion conversion = SkyConverter.convert(midi, options);
                String stem = stemOf(input.name);
                String folderName = uniqueFolderName(stem, usedFolderNames);
                Uri songDirectory = TreeDocumentStore.ensureDirectory(
                        resolver,
                        outputTree,
                        outputRoot,
                        folderName
                );
                JsonWriter.ArtifactNames artifacts = writeArtifacts(
                        resolver,
                        outputTree,
                        songDirectory,
                        input.name,
                        stem,
                        midi,
                        conversion
                );
                TreeDocumentStore.writeUtf8(
                        resolver,
                        outputTree,
                        songDirectory,
                        artifacts.reportJson,
                        JsonWriter.reportPayload(conversion, input.name, artifacts)
                );
                String message = "完成：黑白 " + artifacts.blackPngPages.size()
                        + " 张，彩谱 " + artifacts.colorMobilePngPages.size() + " 张";
                outcomes.add(FileOutcome.success(input.name, message));
                progress.onLog(message + " → " + folderName);
            } catch (Exception error) {
                String message = readableMessage(error);
                outcomes.add(FileOutcome.failure(input.name, message));
                progress.onLog("失败：" + input.name + "：" + message);
            }
        }
        BatchSummary summary = summarize(outcomes);
        progress.onFinished(summary);
        return summary;
    }

    private static JsonWriter.ArtifactNames writeArtifacts(
            ContentResolver resolver,
            Uri outputTree,
            Uri songDirectory,
            String sourceFilename,
            String stem,
            MidiFileReader.Result midi,
            SkyConverter.Conversion conversion
    ) throws IOException {
        List<String> blackPages = writePageFamily(
                resolver,
                outputTree,
                songDirectory,
                stem + ".sky-",
                conversion.blackImages,
                conversion.title,
                true
        );
        List<String> colorMobilePages = writePageFamily(
                resolver,
                outputTree,
                songDirectory,
                stem + ".color-mobile-",
                conversion.colorImages,
                conversion.title,
                false
        );
        String blackJson = stem + ".sky.json";
        String colorJson = stem + ".color.json";
        String notesJson = stem + ".notes.json";
        String reportJson = stem + ".report.json";
        TreeDocumentStore.writeUtf8(
                resolver,
                outputTree,
                songDirectory,
                blackJson,
                JsonWriter.blackPayload(conversion, sourceFilename, conversion.title, blackPages)
        );
        TreeDocumentStore.writeUtf8(
                resolver,
                outputTree,
                songDirectory,
                colorJson,
                JsonWriter.colorPayload(conversion, sourceFilename, conversion.title, colorMobilePages)
        );
        TreeDocumentStore.writeUtf8(
                resolver,
                outputTree,
                songDirectory,
                notesJson,
                JsonWriter.notesPayload(midi, sourceFilename, conversion.title)
        );
        return new JsonWriter.ArtifactNames(
                blackPages,
                Collections.emptyList(),
                colorMobilePages,
                blackPages.isEmpty() ? null : blackPages.get(0),
                colorMobilePages.isEmpty() ? null : colorMobilePages.get(0),
                notesJson,
                reportJson,
                blackJson,
                colorJson
        );
    }

    private static List<String> writePageFamily(
            ContentResolver resolver,
            Uri outputTree,
            Uri songDirectory,
            String prefix,
            List<SkyConverter.ColorImage> images,
            String title,
            boolean blackOnly
    ) throws IOException {
        int pageCount = Math.max(
                1,
                (int) Math.ceil((images == null ? 0 : images.size())
                        / (double) SkyPageRenderer.MOBILE_PAGE_SIZE)
        );
        List<String> numberedPages = new ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            int from = page * SkyPageRenderer.MOBILE_PAGE_SIZE;
            int to = Math.min(from + SkyPageRenderer.MOBILE_PAGE_SIZE, images == null ? 0 : images.size());
            List<SkyConverter.ColorImage> pageImages = images == null
                    ? Collections.emptyList()
                    : images.subList(from, to);
            String name = prefix + String.format(Locale.ROOT, "%03d", page + 1) + ".png";
            byte[] png = SkyPageRenderer.renderMobilePage(
                    pageImages,
                    title,
                    page + 1,
                    pageCount,
                    blackOnly
            );
            TreeDocumentStore.writeBytes(
                    resolver,
                    outputTree,
                    songDirectory,
                    name,
                    "image/png",
                    png
            );
            numberedPages.add(name);
        }
        String coverName = prefix + "000.png";
        byte[] cover = SkyPageRenderer.renderCoverPage(
                readExistingPage(resolver, outputTree, songDirectory, numberedPages.get(0)),
                title
        );
        TreeDocumentStore.writeBytes(
                resolver,
                outputTree,
                songDirectory,
                coverName,
                "image/png",
                cover
        );
        List<String> allPages = new ArrayList<>();
        allPages.add(coverName);
        allPages.addAll(numberedPages);
        return allPages;
    }

    private static byte[] readExistingPage(
            ContentResolver resolver,
            Uri outputTree,
            Uri songDirectory,
            String name
    ) throws IOException {
        for (TreeDocumentStore.Entry entry : TreeDocumentStore.listChildren(
                resolver,
                outputTree,
                songDirectory
        )) {
            if (!entry.directory && entry.name.equals(name)) {
                return TreeDocumentStore.readBytes(resolver, entry.uri);
            }
        }
        throw new IOException("分页 PNG 写入后无法重新读取：" + name);
    }

    private static void addSkipped(
            List<FileOutcome> outcomes,
            List<TreeDocumentStore.Entry> inputFiles,
            int fromIndex
    ) {
        for (int index = fromIndex; index < inputFiles.size(); index++) {
            outcomes.add(FileOutcome.skipped(inputFiles.get(index).name));
        }
    }

    private static String stemOf(String filename) {
        String name = filename == null ? "" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".midi")) {
            name = name.substring(0, name.length() - 5);
        } else if (lower.endsWith(".mid")) {
            name = name.substring(0, name.length() - 4);
        }
        return safeName(name);
    }

    private static String trimTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character == '.' || character == ' ') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static boolean isReservedWindowsName(String value) {
        String base = value;
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        String upper = base.toUpperCase(Locale.ROOT);
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")) {
            return true;
        }
        if (upper.length() == 4 && (upper.startsWith("COM") || upper.startsWith("LPT"))) {
            char digit = upper.charAt(3);
            return digit >= '1' && digit <= '9';
        }
        return false;
    }

    private static boolean containsIgnoreCase(Set<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.trim();
    }
}
