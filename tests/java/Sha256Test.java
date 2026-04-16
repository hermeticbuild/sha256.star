package tests.java;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import net.starlark.java.syntax.SyntaxError;

/**
 * Data-driven test runner for sha256.star.
 *
 * <p>Discovers test pairs in tests/testdata/: for each {@code foo.star} there
 * must be a matching {@code foo.expected}. The runner loads sha256.star into a
 * Starlark module, executes each test script, and compares captured print output
 * against the expected file.
 *
 * <p>Expected files starting with {@code ERROR:} match against EvalException messages.
 *
 * <p>Supports Bazel test protocol:
 * <ul>
 *   <li>{@code XML_OUTPUT_FILE} writes JUnit XML results if set
 *   <li>{@code TESTBRIDGE_TEST_ONLY} filters test cases by name (from {@code --test_filter})
 * </ul>
 */
@AutoBazelRepository
public final class Sha256Test {

    private static final String CLASSNAME = "tests.java.Sha256Test";

    /** Result of a single test case. */
    private static final class TestResult {
        final String name;
        final boolean passed;
        final double durationSecs;
        final String failureMessage;  // null if passed

        TestResult(String name, boolean passed, double durationSecs, String failureMessage) {
            this.name = name;
            this.passed = passed;
            this.durationSecs = durationSecs;
            this.failureMessage = failureMessage;
        }
    }

    public static void main(String[] args) throws Exception {
        Runfiles.Preloaded preloaded = Runfiles.preload();
        Runfiles runfiles = preloaded.withSourceRepository(AutoBazelRepository_Sha256Test.NAME);

        String libPath = runfiles.rlocation("_main/sha256.star");
        String testdataDir = Paths.get(
                runfiles.rlocation("_main/tests/testdata/abc.star")).getParent().toString();

        // Discover test pairs
        List<String> testNames = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(Paths.get(testdataDir), "*.star")) {
            for (Path p : stream) {
                String fname = p.getFileName().toString();
                testNames.add(fname.substring(0, fname.length() - ".star".length()));
            }
        }
        Collections.sort(testNames);

        // Apply --test_filter via TESTBRIDGE_TEST_ONLY
        String testFilter = System.getenv("TESTBRIDGE_TEST_ONLY");
        if (testFilter != null && !testFilter.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String name : testNames) {
                if (name.contains(testFilter)) {
                    filtered.add(name);
                }
            }
            testNames = filtered;
        }

        if (testNames.isEmpty()) {
            System.out.println("0 passed, 0 failed (0 total)");
            writeXmlIfRequested(Collections.emptyList(), 0.0);
            return;
        }

        long suiteStartNanos = System.nanoTime();
        List<TestResult> results = new ArrayList<>();

        for (String name : testNames) {
            Path starFile = Paths.get(testdataDir, name + ".star");
            Path expectedFile = Paths.get(testdataDir, name + ".expected");

            if (!isLanguageEnabled(starFile, "java")) {
                System.out.printf("SKIP: %s%n", name);
                continue;
            }

            long startNanos = System.nanoTime();

            if (!Files.exists(expectedFile)) {
                double secs = elapsedSecs(startNanos);
                String msg = "missing " + name + ".expected";
                System.err.printf("FAIL: %s %s%n", name, msg);
                results.add(new TestResult(name, false, secs, msg));
                continue;
            }

            String expected = Files.readString(expectedFile).stripTrailing();
            String actual;
            try {
                actual = runTestScript(libPath, starFile).stripTrailing();
            } catch (SyntaxError.Exception e) {
                double secs = elapsedSecs(startNanos);
                String msg = "syntax error: " + e.getMessage();
                System.err.printf("FAIL: %s %s%n", name, msg);
                results.add(new TestResult(name, false, secs, msg));
                continue;
            } catch (EvalException e) {
                double secs = elapsedSecs(startNanos);
                if (expected.startsWith("ERROR:")) {
                    String expectedMsg = expected.substring("ERROR:".length()).strip();
                    if (e.getMessage().contains(expectedMsg)) {
                        System.out.printf("PASS: %s%n", name);
                        results.add(new TestResult(name, true, secs, null));
                        continue;
                    }
                    String msg = "wrong error\n  expected: " + expectedMsg + "\n  actual:   " + e.getMessage();
                    System.err.printf("FAIL: %s %s%n", name, msg);
                    results.add(new TestResult(name, false, secs, msg));
                    continue;
                }
                String msg = "unexpected EvalException: " + e.getMessage();
                System.err.printf("FAIL: %s %s%n", name, msg);
                results.add(new TestResult(name, false, secs, msg));
                continue;
            }

            double secs = elapsedSecs(startNanos);
            if (expected.equals(actual)) {
                System.out.printf("PASS: %s%n", name);
                results.add(new TestResult(name, true, secs, null));
            } else {
                String msg = "expected: " + expected + "\nactual:   " + actual;
                System.err.printf("FAIL: %s%n  %s%n", name, msg);
                results.add(new TestResult(name, false, secs, msg));
            }
        }

        double totalSecs = elapsedSecs(suiteStartNanos);
        int passed = 0;
        int failed = 0;
        for (TestResult r : results) {
            if (r.passed) passed++;
            else failed++;
        }

        System.out.printf("%n%d passed, %d failed (%d total)%n", passed, failed, passed + failed);
        writeXmlIfRequested(results, totalSecs);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static double elapsedSecs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    /**
     * Check if a test file is enabled for the given language.
     * If the first line is {@code # languages: java, go} then only those languages run it.
     * If there is no such marker, all languages run the test.
     */
    private static boolean isLanguageEnabled(Path starFile, String language) {
        try {
            String firstLine = Files.readString(starFile).lines().findFirst().orElse("");
            if (firstLine.startsWith("# languages:")) {
                String langs = firstLine.substring("# languages:".length());
                for (String lang : langs.split(",")) {
                    if (lang.strip().equals(language)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (IOException ignored) {
        }
        return true;
    }

    /** Write JUnit XML to XML_OUTPUT_FILE if the environment variable is set. */
    private static void writeXmlIfRequested(List<TestResult> results, double totalSecs)
            throws IOException {
        String xmlPath = System.getenv("XML_OUTPUT_FILE");
        if (xmlPath == null || xmlPath.isEmpty()) {
            return;
        }

        int failures = 0;
        for (TestResult r : results) {
            if (!r.passed) failures++;
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format("<testsuites>\n"));
        xml.append(String.format("  <testsuite name=\"Sha256Test\" tests=\"%d\" failures=\"%d\" time=\"%.3f\">\n",
                results.size(), failures, totalSecs));

        for (TestResult r : results) {
            xml.append(String.format("    <testcase name=\"%s\" classname=\"%s\" time=\"%.3f\"",
                    escapeXml(r.name), CLASSNAME, r.durationSecs));
            if (r.passed) {
                xml.append("/>\n");
            } else {
                xml.append(">\n");
                xml.append(String.format("      <failure message=\"%s\">%s</failure>\n",
                        escapeXml(r.failureMessage.split("\n")[0]),
                        escapeXml(r.failureMessage)));
                xml.append("    </testcase>\n");
            }
        }

        xml.append("  </testsuite>\n");
        xml.append("</testsuites>\n");

        Files.writeString(Paths.get(xmlPath), xml.toString());
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String runTestScript(String libPath, Path testScript) throws Exception {
        Mutability mu = Mutability.create("test");
        Module module = Module.create();
        StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);

        // Load the library (suppress prints during load)
        thread.setPrintHandler((th, msg) -> {});
        Starlark.execFile(ParserInput.readFile(libPath), FileOptions.DEFAULT, module, thread);

        // Execute the test script, capturing prints
        StringBuilder output = new StringBuilder();
        thread.setPrintHandler((th, msg) -> {
            if (output.length() > 0) {
                output.append("\n");
            }
            output.append(msg);
        });

        Starlark.execFile(
                ParserInput.readFile(testScript.toString()),
                FileOptions.DEFAULT, module, thread);

        mu.close();
        return output.toString();
    }
}
