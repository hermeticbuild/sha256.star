package interpreter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;

import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;

/**
 * Pure-Starlark sha256sum: computes SHA-256 hex digests, matching coreutils
 * sha256sum output format.
 *
 * <p>Uses streaming builtins ({@code open_bytes}, {@code stdin_bytes}) so that
 * input is read lazily with buffering through the Starlark SHA-256
 * implementation, avoiding materializing the entire input in memory.
 *
 * <pre>
 *   echo -n hello | bazel run //interpreter:sha256sum
 *   # 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824  -
 *
 *   bazel run //interpreter:sha256sum -- myfile.txt
 *   # 2cf24dba...  myfile.txt
 * </pre>
 */
@AutoBazelRepository
public final class Sha256sum {

    public static void main(String[] args) throws Exception {
        Runfiles.Preloaded preloaded = Runfiles.preload();
        Runfiles runfiles = preloaded.withSourceRepository(AutoBazelRepository_Sha256sum.NAME);
        String libPath = runfiles.rlocation("sha256.star/sha256.star");

        String bwd = System.getenv("BUILD_WORKING_DIRECTORY");
        OpenBytesBuiltin openBytes = new OpenBytesBuiltin(bwd);
        ByteIterable stdinBytes = ByteIterable.fromStream(System.in, "stdin_bytes");

        Mutability mu = Mutability.create("sha256sum");
        Module module = Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                Map.of("open_bytes", openBytes, "stdin_bytes", stdinBytes));
        StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
        thread.setPrintHandler((th, msg) -> System.out.println(msg));

        Starlark.execFile(ParserInput.readFile(libPath), FileOptions.DEFAULT, module, thread);

        if (args.length == 0) {
            exec(module, thread, "print(sha256(stdin_bytes, raw = True) + \"  -\")");
        } else {
            for (String arg : args) {
                Path path = Paths.get(arg);
                if (!path.isAbsolute() && bwd != null) {
                    path = Paths.get(bwd).resolve(path);
                }
                String absPath = escape(path.toString());
                String label = escape(arg);
                exec(module, thread,
                        "print(sha256(open_bytes(\"" + absPath + "\"), raw = True) + \"  " + label + "\")");
            }
        }

        mu.close();
    }

    private static void exec(Module module, StarlarkThread thread, String script) throws Exception {
        Starlark.execFile(
                ParserInput.fromString(script, "<sha256sum>"),
                FileOptions.DEFAULT, module, thread);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
