package com.zxw.fitness.data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.zxw.fitness.util.StreamCopy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把 App 私有的原始视频复制到系统文件选择器返回的 URI。
 * 进程内只允许一个任务；不持有 Activity，页面重建或切换记录不改变源文件。
 */
public final class VideoExporter {
    public interface Listener {
        void onVideoExportFinished(boolean success, String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static boolean running;

    private VideoExporter() { }

    public static synchronized void attach(Listener value) {
        listener = new WeakReference<>(value);
    }

    public static synchronized void detach(Listener value) {
        if (listener.get() == value) listener.clear();
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    /** 必须持有目标 URI 的临时写入授权；返回 false 表示已有任务或任务启动失败。 */
    public static synchronized boolean start(Context context, final File source, final Uri destination) {
        if (running || context == null || source == null || destination == null) return false;
        final Context app = context.getApplicationContext();
        running = true;
        try {
            EXECUTOR.execute(new Runnable() {
                @Override public void run() {
                    boolean success = false;
                    String message;
                    try {
                        long expected = source.length();
                        if (!source.isFile() || expected <= 0) {
                            throw new IOException("Missing or empty source");
                        }
                        // 先打开源文件，再打开目标，避免源文件已消失时改写用户选择的目标。
                        try (InputStream input = new FileInputStream(source)) {
                            // wt 明确要求截断；不能用含义因提供方而异的 w，避免覆盖较短文件时留下旧尾巴。
                            OutputStream opened = app.getContentResolver().openOutputStream(destination, "wt");
                            if (opened == null) throw new IOException("Cannot open destination");
                            try (OutputStream output = opened) {
                                long copied = StreamCopy.copy(input, output);
                                if (copied != expected || source.length() != expected) {
                                    throw new IOException("Source changed during export");
                                }
                            }
                        }
                        success = true;
                        message = "原视频已导出到所选位置（不含骨架叠加）";
                    } catch (IOException | RuntimeException e) {
                        // 文档选择器也可能允许覆盖已有文件，不能擅自删除 URI。
                        message = "导出失败，请检查源视频和目标空间；目标文件可能不完整，请检查或删除";
                    }
                    final boolean completed = success;
                    final String result = message;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            Listener current;
                            synchronized (VideoExporter.class) {
                                running = false;
                                current = listener.get();
                            }
                            if (current != null) {
                                current.onVideoExportFinished(completed, result);
                            } else {
                                Toast.makeText(app, result, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
            return true;
        } catch (RuntimeException e) {
            running = false;
            return false;
        }
    }
}
