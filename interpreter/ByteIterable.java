package interpreter;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkIterable;

/**
 * A Starlark-iterable that streams bytes from an {@link InputStream} with
 * buffering.
 *
 * <p>Each element yielded is a {@link StarlarkInt} in 0–255. The stream is read
 * lazily via a {@link BufferedInputStream} so that large inputs don't need to be
 * materialized as a Starlark list.
 */
final class ByteIterable implements StarlarkIterable<StarlarkInt> {

    private final InputStream source;
    private final String label;

    private ByteIterable(InputStream source, String label) {
        this.source = source;
        this.label = label;
    }

    /** Create a byte iterable that lazily reads from a file. */
    static ByteIterable fromFile(Path path) {
        try {
            InputStream raw = Files.newInputStream(path);
            return new ByteIterable(
                    new BufferedInputStream(raw, 64 * 1024),
                    "open_bytes(" + path + ")");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Create a byte iterable that lazily reads from an input stream (e.g. stdin). */
    static ByteIterable fromStream(InputStream stream, String label) {
        return new ByteIterable(
                new BufferedInputStream(stream, 64 * 1024),
                label);
    }

    @Override
    public Iterator<StarlarkInt> iterator() {
        return new Iterator<>() {
            private int next = -2; // -2 = not read yet, -1 = EOF

            private void advance() {
                if (next == -2) {
                    try {
                        next = source.read();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next >= 0;
            }

            @Override
            public StarlarkInt next() {
                advance();
                if (next < 0) {
                    try { source.close(); } catch (IOException ignored) {}
                    throw new NoSuchElementException();
                }
                StarlarkInt val = StarlarkInt.of(next);
                next = -2;
                return val;
            }
        };
    }

    @Override
    public void repr(Printer printer) {
        printer.append(label);
    }

    private static class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) { super(cause); }
    }
}
