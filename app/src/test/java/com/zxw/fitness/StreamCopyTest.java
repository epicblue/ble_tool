package com.zxw.fitness;

import com.zxw.fitness.util.StreamCopy;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

import static org.junit.Assert.*;

/** 大文件分块复制、错误传播及由调用方负责关闭的约定。 */
public class StreamCopyTest {
    @Test public void copiesBytesExactlyWithoutClosingCallerStreams() throws Exception {
        byte[] source = new byte[2 * 1024 * 1024 + 123];
        new Random(42).nextBytes(source);
        TrackingInput input = new TrackingInput(source);
        TrackingOutput output = new TrackingOutput();
        assertEquals(source.length, StreamCopy.copy(input, output));
        assertArrayEquals(source, output.toByteArray());
        assertFalse(input.closed);
        assertFalse(output.closed);
        assertTrue(output.flushed);
    }

    @Test public void writeFailureIsNotReportedAsSuccess() throws Exception {
        try {
            StreamCopy.copy(new ByteArrayInputStream(new byte[128 * 1024]), new OutputStream() {
                @Override public void write(int b) throws IOException { throw new IOException("disk full"); }
                @Override public void write(byte[] b, int offset, int length) throws IOException {
                    throw new IOException("disk full");
                }
            });
            fail("write error must propagate");
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        }
    }

    @Test public void flushFailureIsNotReportedAsSuccess() throws Exception {
        try {
            StreamCopy.copy(new ByteArrayInputStream(new byte[]{1, 2, 3}), new ByteArrayOutputStream() {
                @Override public void flush() throws IOException { throw new IOException("flush failed"); }
            });
            fail("flush error must propagate");
        } catch (IOException expected) {
            assertEquals("flush failed", expected.getMessage());
        }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] data) { super(data); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        boolean flushed;
        @Override public void close() throws IOException { closed = true; super.close(); }
        @Override public void flush() throws IOException { flushed = true; super.flush(); }
    }
}
