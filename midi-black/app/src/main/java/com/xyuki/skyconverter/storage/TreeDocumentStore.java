package com.xyuki.skyconverter.storage;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * DocumentsContract helpers for persisted Storage Access Framework tree grants.
 * No filesystem path is inferred from a content URI.
 */
public final class TreeDocumentStore {
    private static final String[] CHILD_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
    };

    private TreeDocumentStore() {
    }

    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final String mimeType;
        public final boolean directory;
        public final int flags;

        private Entry(Uri uri, String name, String mimeType, int flags) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(this.mimeType);
            this.flags = flags;
        }
    }

    public static Uri rootDocumentUri(Uri treeUri) {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    public static List<Entry> listChildren(
            ContentResolver resolver,
            Uri treeUri,
            Uri parentDocumentUri
    ) throws IOException {
        String documentId = documentId(parentDocumentUri, treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null);
        if (cursor == null) {
            throw new IOException("无法读取文件夹内容：" + parentDocumentUri);
        }
        List<Entry> entries = new ArrayList<>();
        try (Cursor ignored = cursor) {
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int flagsColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                throw new IOException("文件夹提供程序缺少标准 DocumentsContract 字段");
            }
            while (cursor.moveToNext()) {
                String childId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                int flags = flagsColumn < 0 ? 0 : cursor.getInt(flagsColumn);
                entries.add(new Entry(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        name,
                        mime,
                        flags
                ));
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return entries;
    }

    public static List<Entry> findMidiFiles(
            ContentResolver resolver,
            Uri treeUri
    ) throws IOException {
        List<Entry> result = new ArrayList<>();
        Deque<Uri> pending = new ArrayDeque<>();
        pending.add(rootDocumentUri(treeUri));
        while (!pending.isEmpty()) {
            Uri directory = pending.removeFirst();
            for (Entry entry : listChildren(resolver, treeUri, directory)) {
                if (entry.directory) {
                    pending.addLast(entry.uri);
                } else if (isMidi(entry.name)) {
                    result.add(entry);
                }
            }
        }
        result.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    public static Uri ensureDirectory(
            ContentResolver resolver,
            Uri treeUri,
            Uri parentDocumentUri,
            String name
    ) throws IOException {
        Entry existing = findChild(resolver, treeUri, parentDocumentUri, name, true);
        if (existing != null) {
            return existing.uri;
        }
        Uri created = DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) {
            throw new IOException("无法创建输出文件夹：" + name);
        }
        return created;
    }

    public static void writeUtf8(
            ContentResolver resolver,
            Uri treeUri,
            Uri parentDocumentUri,
            String name,
            String content
    ) throws IOException {
        writeBytes(
                resolver,
                treeUri,
                parentDocumentUri,
                name,
                "application/json",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    public static void writeBytes(
            ContentResolver resolver,
            Uri treeUri,
            Uri parentDocumentUri,
            String name,
            String mimeType,
            byte[] content
    ) throws IOException {
        Entry existing = findChild(resolver, treeUri, parentDocumentUri, name, false);
        Uri fileUri = existing == null
                ? DocumentsContract.createDocument(resolver, parentDocumentUri, mimeType, name)
                : existing.uri;
        if (fileUri == null) {
            throw new IOException("无法创建输出文件：" + name);
        }
        try (OutputStream output = resolver.openOutputStream(fileUri, "w")) {
            if (output == null) {
                throw new IOException("无法写入输出文件：" + name);
            }
            output.write(content);
            output.flush();
        }
    }

    public static byte[] readBytes(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("无法打开输入文件：" + uri);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }

    public static String displayName(ContentResolver resolver, Uri treeUri) {
        Uri documentUri = rootDocumentUri(treeUri);
        Cursor cursor = resolver.query(
                documentUri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        );
        if (cursor != null) {
            try (Cursor ignored = cursor) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && cursor.moveToFirst()) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return treeUri.toString();
    }

    public static boolean isMidi(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mid") || lower.endsWith(".midi");
    }

    private static Entry findChild(
            ContentResolver resolver,
            Uri treeUri,
            Uri parentDocumentUri,
            String name,
            boolean directory
    ) throws IOException {
        for (Entry entry : listChildren(resolver, treeUri, parentDocumentUri)) {
            if (entry.directory == directory && entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static String documentId(Uri parentDocumentUri, Uri treeUri) {
        String value = parentDocumentUri.toString();
        if (value.contains("/tree/")) {
            return DocumentsContract.getTreeDocumentId(parentDocumentUri);
        }
        return DocumentsContract.getDocumentId(parentDocumentUri);
    }
}
