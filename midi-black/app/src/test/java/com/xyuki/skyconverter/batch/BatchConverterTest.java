package com.xyuki.skyconverter.batch;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class BatchConverterTest {
    @Test
    public void sanitizesNamesAndKeepsDuplicateOutputFoldersDistinct() {
        assertEquals("a_b_c", BatchConverter.safeName("a<b:c>"));
        assertEquals("未命名", BatchConverter.safeName("   ...   "));

        Set<String> used = new LinkedHashSet<>();
        assertEquals("song", BatchConverter.uniqueFolderName("song", used));
        assertEquals("song (2)", BatchConverter.uniqueFolderName("song", used));
        assertEquals("song (3)", BatchConverter.uniqueFolderName("song", used));
    }

    @Test
    public void summarizesIndependentSuccessFailureAndSkippedFiles() {
        BatchConverter.BatchSummary summary = BatchConverter.summarize(List.of(
                BatchConverter.FileOutcome.success("one.mid"),
                BatchConverter.FileOutcome.failure("bad.mid", "格式错误"),
                BatchConverter.FileOutcome.skipped("later.mid")
        ));

        assertEquals(1, summary.successCount);
        assertEquals(1, summary.failureCount);
        assertEquals(1, summary.skippedCount);
        assertEquals(3, summary.outcomes.size());
        assertEquals("bad.mid", summary.outcomes.get(1).name);
    }
}
