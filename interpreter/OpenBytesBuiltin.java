package interpreter;

import java.nio.file.Path;
import java.nio.file.Paths;

import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkValue;

/**
 * A Starlark builtin callable: {@code open_bytes(path)} returns a buffered,
 * lazily-read byte iterable over the given file.
 *
 * <p>Registered as a predeclared global named {@code open_bytes} so that
 * Starlark code can call {@code open_bytes("path/to/file")} directly.
 */
@StarlarkBuiltin(name = "open_bytes", doc = "Open a file as a byte iterable.", documented = false)
final class OpenBytesBuiltin implements StarlarkValue {

    private final String workingDirectory;

    /**
     * @param workingDirectory resolved against relative paths (typically
     *     BUILD_WORKING_DIRECTORY or cwd)
     */
    OpenBytesBuiltin(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    @StarlarkMethod(
        name = "open_bytes",
        selfCall = true,
        doc = "Return a byte-iterable over the contents of the given file. "
            + "Each element is an int in 0-255. The file is read lazily with buffering.",
        parameters = {@Param(name = "path", doc = "File path to read.")})
    public ByteIterable invoke(String pathStr) {
        Path path = Paths.get(pathStr);
        if (!path.isAbsolute() && workingDirectory != null) {
            path = Paths.get(workingDirectory).resolve(path);
        }
        return ByteIterable.fromFile(path);
    }
}
