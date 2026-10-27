package com.zxw.fitness;

import android.content.Context;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.data.VideoExporter;
import com.zxw.fitness.util.StreamCopy;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 测试 Android ContentResolver 路径；系统文件选择器仍需手动在设备上验证。 */
@RunWith(AndroidJUnit4.class)
public class VideoExporterTest {
    @Test public void copyPreservesSourceAndTruncatesLongerDestination() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File source = File.createTempFile("video-source-", ".mp4", context.getCacheDir());
        File destination = File.createTempFile("video-dest-", ".mp4", context.getCacheDir());
        Callback callback = new Callback();
        try {
            byte[] original = new byte[1024 * 1024 + 101];
            new Random(11).nextBytes(original);
            write(source, original);
            write(destination, new byte[original.length + 1000]);
            VideoExporter.attach(callback);
            // file URI 仅供同进程测试 ContentResolver，实际界面只接受选择器的 content URI。
            assertTrue(VideoExporter.start(context, source, Uri.fromFile(destination)));
            assertTrue(callback.latch.await(30, TimeUnit.SECONDS));
            assertTrue(callback.message, callback.success);
            assertFalse(VideoExporter.isRunning());
            assertArrayEquals(original, read(source));
            assertArrayEquals(original, read(destination));
        } finally {
            VideoExporter.detach(callback);
            source.delete();
            destination.delete();
        }
    }

    @Test public void missingSourceDoesNotTouchExistingDestination() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File missing = new File(context.getCacheDir(), "missing-" + System.nanoTime() + ".mp4");
        File destination = File.createTempFile("video-existing-", ".mp4", context.getCacheDir());
        byte[] originalDestination = {9, 8, 7, 6};
        Callback callback = new Callback();
        try {
            write(destination, originalDestination);
            VideoExporter.attach(callback);
            assertTrue(VideoExporter.start(context, missing, Uri.fromFile(destination)));
            assertTrue(callback.latch.await(30, TimeUnit.SECONDS));
            assertFalse(callback.success);
            assertArrayEquals(originalDestination, read(destination));
        } finally {
            VideoExporter.detach(callback);
            destination.delete();
        }
    }

    @Test public void inaccessibleProviderReportsFailureWithoutChangingSource() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File source = File.createTempFile("video-source-", ".mp4", context.getCacheDir());
        byte[] original = {1, 3, 5, 7};
        Callback callback = new Callback();
        try {
            write(source, original);
            VideoExporter.attach(callback);
            Uri unavailable = Uri.parse("content://com.zxw.invalid.test.provider/document/1");
            assertTrue(VideoExporter.start(context, source, unavailable));
            assertTrue(callback.latch.await(30, TimeUnit.SECONDS));
            assertFalse(callback.success);
            assertArrayEquals(original, read(source));
        } finally {
            VideoExporter.detach(callback);
            source.delete();
        }
    }

    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StreamCopy.copy(in, out);
            return out.toByteArray();
        }
    }

    private static final class Callback implements VideoExporter.Listener {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success;
        volatile String message;
        @Override public void onVideoExportFinished(boolean value, String text) {
            success = value;
            message = text;
            latch.countDown();
        }
    }
}
