package com.xyuki.skycolor.converter.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders the small phone-oriented color-score pages and the yellow title cover. */
public final class ColorPageRenderer {
    public static final int MOBILE_PAGE_SIZE = 32;
    private static final int MOBILE_COLUMNS = 4;
    private static final int CARD_WIDTH = 138;
    private static final int CARD_HEIGHT = 84;
    private static final int GAP_X = 6;
    private static final int GAP_Y = 10;
    private static final int MARGIN_X = 15;
    private static final int HEADER_HEIGHT = 90;
    private static final int BOTTOM_MARGIN = 18;
    private static final int GRID_COLOR = 0xFFA7ADB3;
    private static final int TITLE_COLOR = 0xFF7569D8;
    private static final int PAGE_NUMBER_COLOR = 0xFF6B7280;
    private static final int MOBILE_BLUE = 0xFF22C7E8;

    private ColorPageRenderer() {
    }

    public static byte[] renderMobilePage(
            List<ColorScoreConverter.ColorImage> images,
            String title,
            int pageNumber,
            int pageCount
    ) {
        int imageCount = images == null ? 0 : images.size();
        int rows = Math.max(1, (int) Math.ceil(imageCount / (double) MOBILE_COLUMNS));
        int width = MARGIN_X * 2 + MOBILE_COLUMNS * CARD_WIDTH + (MOBILE_COLUMNS - 1) * GAP_X;
        int height = HEADER_HEIGHT + rows * CARD_HEIGHT + (rows - 1) * GAP_Y + BOTTOM_MARGIN;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(48f);
        paint.setColor(TITLE_COLOR);
        canvas.drawText(nonEmpty(title, "未命名歌曲"), MARGIN_X, 58f, paint);
        paint.setTextSize(24f);
        paint.setColor(PAGE_NUMBER_COLOR);
        String pageLabel = Math.max(1, pageNumber) + "/" + Math.max(1, pageCount);
        float labelWidth = paint.measureText(pageLabel);
        canvas.drawText(pageLabel, width - MARGIN_X - labelWidth, 53f, paint);

        int count = Math.min(imageCount, MOBILE_PAGE_SIZE);
        for (int localIndex = 0; localIndex < count; localIndex++) {
            ColorScoreConverter.ColorImage image = images.get(localIndex);
            int row = localIndex / MOBILE_COLUMNS;
            int column = localIndex % MOBILE_COLUMNS;
            float left = MARGIN_X + column * (CARD_WIDTH + GAP_X);
            float top = HEADER_HEIGHT + row * (CARD_HEIGHT + GAP_Y);
            drawImage(canvas, paint, image, left, top);
        }
        return toPng(bitmap);
    }

    public static byte[] renderCoverPage(byte[] firstPagePng, String title) {
        if (firstPagePng == null || firstPagePng.length == 0) {
            throw new IllegalArgumentException("第一页 PNG 为空，不能生成封面");
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(firstPagePng, 0, firstPagePng.length);
        if (decoded == null) {
            throw new IllegalArgumentException("无法读取第一页 PNG，不能生成封面");
        }
        Bitmap bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true);
        if (bitmap == null) {
            decoded.recycle();
            throw new IllegalArgumentException("无法创建可编辑封面位图");
        }
        if (bitmap != decoded) {
            decoded.recycle();
        }
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        String text = nonEmpty(title, "未命名歌曲");
        float textSize = Math.min(112f, bitmap.getWidth() / 5f);
        while (textSize > 22f) {
            paint.setTextSize(textSize);
            if (paint.measureText(text) <= bitmap.getWidth() - 40f) {
                break;
            }
            textSize -= 2f;
        }
        paint.setTextSize(Math.max(22f, textSize));
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = bitmap.getHeight() * 0.55f - (metrics.ascent + metrics.descent) / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, textSize * 0.06f));
        paint.setColor(0xFF111827);
        canvas.drawText(text, bitmap.getWidth() / 2f, baseline, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFD447);
        canvas.drawText(text, bitmap.getWidth() / 2f, baseline, paint);
        byte[] result = toPng(bitmap);
        bitmap.recycle();
        return result;
    }

    private static void drawImage(
            Canvas canvas,
            Paint paint,
            ColorScoreConverter.ColorImage image,
            float left,
            float top
    ) {
        Map<String, Integer> colorByKey = new HashMap<>();
        for (ColorScoreConverter.ColorLayer layer : image.layers) {
            int color = layer.index == 0
                    ? Color.BLACK
                    : layer.index == 1 ? Color.RED : MOBILE_BLUE;
            for (String key : layer.keys) {
                colorByKey.put(key, color);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (int keyIndex = 0; keyIndex < BlackScoreReader.KEY_LABELS.length; keyIndex++) {
            int row = keyIndex / 5;
            int column = keyIndex % 5;
            float cellLeft = left + column * CARD_WIDTH / 5f;
            float cellTop = top + row * CARD_HEIGHT / 3f;
            float cellRight = left + (column + 1) * CARD_WIDTH / 5f;
            float cellBottom = top + (row + 1) * CARD_HEIGHT / 3f;
            Integer color = colorByKey.get(BlackScoreReader.KEY_LABELS[keyIndex]);
            paint.setColor(color == null ? Color.WHITE : color);
            canvas.drawRect(
                    new RectF(cellLeft + 1f, cellTop + 1f, cellRight - 1f, cellBottom - 1f),
                    paint
            );
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(GRID_COLOR);
        for (int column = 0; column <= 5; column++) {
            float x = left + column * CARD_WIDTH / 5f;
            canvas.drawLine(x, top, x, top + CARD_HEIGHT, paint);
        }
        for (int row = 0; row <= 3; row++) {
            float y = top + row * CARD_HEIGHT / 3f;
            canvas.drawLine(left, y, left + CARD_WIDTH, y, paint);
        }
    }

    private static byte[] toPng(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new IllegalStateException("PNG 编码失败");
        }
        return output.toByteArray();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
