package interpreter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
 * Starlark interpreter with sha256() pre-loaded.
 *
 * <p>Reads a Starlark script from stdin and executes it with the {@code sha256}
 * function already available. Usage:
 *
 * <pre>
 *   echo 'print(sha256("hello"))' | bazel run //interpreter
 * </pre>
 */
@AutoBazelRepository
public final class Interpreter {

    public static void main(String[] args) throws Exception {
        Runfiles.Preloaded preloaded = Runfiles.preload();
        Runfiles runfiles = preloaded.withSourceRepository(AutoBazelRepository_Interpreter.NAME);

        String libPath = runfiles.rlocation("sha256.star/sha256.star");

        Mutability mu = Mutability.create("interpreter");
        Module module = Module.create();
        StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
        thread.setPrintHandler((th, msg) -> System.out.println(msg));

        // Load sha256.star to define sha256() in the module
        Starlark.execFile(ParserInput.readFile(libPath), FileOptions.DEFAULT, module, thread);

        // Read and execute stdin
        byte[] inputBytes = System.in.readAllBytes();
        String source = new String(inputBytes, StandardCharsets.UTF_8);
        ParserInput input = ParserInput.fromString(source, "<stdin>");
        Starlark.execFile(input, FileOptions.DEFAULT, module, thread);

        mu.close();
    }
}
