package com.xyuki.skycolor.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Classifies supported score payloads for both the converter and the standalone player. */
public final class ScoreImportReader {
    private ScoreImportReader() {
    }

    public static List<ImportedScore> read(byte[] data, String sourceName) {
        try {
            ColorScoreReader.ColorDocument color = ColorScoreReader.readIfColor(data, sourceName);
            if (color != null) {
                return Collections.singletonList(ImportedScore.colorPreview(
                        sourceName,
                        color
                ));
            }
            List<BlackScoreReader.ScoreDocument> documents = BlackScoreReader.read(data, sourceName);
            List<ImportedScore> result = new ArrayList<>();
            for (int index = 0; index < documents.size(); index++) {
                result.add(ImportedScore.black(
                        sourceName,
                        index,
                        documents.size(),
                        documents.get(index)
                ));
            }
            return Collections.unmodifiableList(result);
        } catch (Exception exception) {
            return Collections.singletonList(ImportedScore.invalid(
                    sourceName,
                    messageOf(exception)
            ));
        }
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    public enum Kind {
        BLACK,
        COLOR_PREVIEW,
        INVALID
    }

    public static final class ImportedScore {
        public final Kind kind;
        public final String sourceName;
        public final int songIndex;
        public final int songCount;
        public final BlackScoreReader.ScoreDocument blackDocument;
        public final ColorScoreReader.ColorDocument colorDocument;
        public final String error;

        private ImportedScore(
                Kind kind,
                String sourceName,
                int songIndex,
                int songCount,
                BlackScoreReader.ScoreDocument blackDocument,
                ColorScoreReader.ColorDocument colorDocument,
                String error
        ) {
            this.kind = kind;
            this.sourceName = sourceName == null || sourceName.trim().isEmpty()
                    ? "输入谱面.json" : sourceName;
            this.songIndex = songIndex;
            this.songCount = Math.max(1, songCount);
            this.blackDocument = blackDocument;
            this.colorDocument = colorDocument;
            this.error = error == null ? "" : error;
        }

        private static ImportedScore black(
                String sourceName,
                int songIndex,
                int songCount,
                BlackScoreReader.ScoreDocument document
        ) {
            return new ImportedScore(
                    Kind.BLACK,
                    sourceName,
                    songIndex,
                    songCount,
                    document,
                    null,
                    ""
            );
        }

        private static ImportedScore colorPreview(
                String sourceName,
                ColorScoreReader.ColorDocument document
        ) {
            return new ImportedScore(
                    Kind.COLOR_PREVIEW,
                    sourceName,
                    0,
                    1,
                    null,
                    document,
                    ""
            );
        }

        private static ImportedScore invalid(String sourceName, String error) {
            return new ImportedScore(
                    Kind.INVALID,
                    sourceName,
                    0,
                    1,
                    null,
                    null,
                    error
            );
        }
    }
}
