package com.zxw.fitness.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 有界缓冲复制；调用方负责打开和关闭两个流。 */
public final class StreamCopy {
    private static final int BUFFER_SIZE = 64 * 1024;

    private StreamCopy() { }

    public static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            output.write(buffer, 0, count);
            copied += count;
        }
        output.flush();
        return copied;
    }
}
