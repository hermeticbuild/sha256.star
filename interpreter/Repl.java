package interpreter;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;

import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * Starlark REPL with {@code sha256()} pre-loaded.
 *
 * <p>Based on the upstream Starlark REPL ({@code net.starlark.java.cmd.Main})
 * but pre-executes sha256.star so that the {@code sha256} function is available
 * in the interactive session.
 *
 * <pre>
 *   $ bazel run //interpreter:repl
 *   Welcome to Starlark (java.starlark.net) + sha256.star
 *   >> sha256("hello")
 *   "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
 * </pre>
 */
@AutoBazelRepository
public final class Repl {

    private static final String START_PROMPT = ">> ";
    private static final String CONTINUATION_PROMPT = ".. ";

    private static final FileOptions OPTIONS =
            FileOptions.DEFAULT.toBuilder()
                    .allowToplevelRebinding(true)
                    .loadBindsGlobally(true)
                    .build();

    public static void main(String[] args) throws Exception {
        Runfiles.Preloaded preloaded = Runfiles.preload();
        Runfiles runfiles = preloaded.withSourceRepository(AutoBazelRepository_Repl.NAME);
        String libPath = runfiles.rlocation("sha256.star/sha256.star");

        Mutability mu = Mutability.create("repl");
        Module module = Module.create();
        StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);
        thread.setPrintHandler((th, msg) -> System.out.println(msg));

        // Pre-load sha256.star
        Starlark.execFile(ParserInput.readFile(libPath), FileOptions.DEFAULT, module, thread);

        // Enter REPL
        readEvalPrintLoop(module, thread);
    }

    private static void readEvalPrintLoop(Module module, StarlarkThread thread) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        module.setDocumentation("<REPL>");
        System.err.println("Welcome to Starlark (java.starlark.net) + sha256.star");

        String input;
        while ((input = prompt(reader)) != null) {
            ParserInput parserInput = ParserInput.fromString(input, "<stdin>");
            try {
                Object result = Starlark.execFile(parserInput, OPTIONS, module, thread);
                if (result != Starlark.NONE) {
                    System.out.println(Starlark.repr(result));
                }
            } catch (SyntaxError.Exception ex) {
                for (SyntaxError error : ex.errors()) {
                    System.err.println(error);
                }
            } catch (EvalException ex) {
                System.err.println(ex.getMessageWithStack());
            } catch (InterruptedException ex) {
                System.err.println("interrupted");
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Read a complete Starlark statement from the user. Uses {@code >> } for the
     * first line and {@code .. } for continuation lines. A blank line after
     * continuation signals end of input for that statement.
     */
    private static String prompt(BufferedReader reader) {
        StringBuilder buf = new StringBuilder();
        String prompt = START_PROMPT;

        try {
            while (true) {
                System.err.print(prompt);
                System.err.flush();
                String line = reader.readLine();
                if (line == null) {
                    return buf.length() > 0 ? buf.toString() : null;
                }

                if (prompt.equals(CONTINUATION_PROMPT) && line.isEmpty()) {
                    return buf.toString();
                }

                if (buf.length() > 0) {
                    buf.append("\n");
                }
                buf.append(line);

                // Check if the input so far parses as a complete statement.
                // If not, continue reading.
                StarlarkFile file = StarlarkFile.parse(
                        ParserInput.fromString(buf.toString(), "<stdin>"), OPTIONS);
                if (file.ok() && !file.getStatements().isEmpty()) {
                    return buf.toString();
                }

                prompt = CONTINUATION_PROMPT;
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
