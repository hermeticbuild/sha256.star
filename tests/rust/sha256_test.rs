use std::cell::RefCell;
use std::env;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::Instant;

use starlark::environment::{Globals, LibraryExtension, Module};
use starlark::eval::Evaluator;
use starlark::PrintHandler;
use starlark::syntax::{AstModule, Dialect};

/// PrintHandler that captures output into a shared Vec.
struct CapturePrintHandler<'a> {
    lines: &'a RefCell<Vec<String>>,
}

impl PrintHandler for CapturePrintHandler<'_> {
    fn println(&self, text: &str) -> anyhow::Result<()> {
        self.lines.borrow_mut().push(text.to_owned());
        Ok(())
    }
}

/// PrintHandler that discards output.
struct SuppressPrintHandler;

impl PrintHandler for SuppressPrintHandler {
    fn println(&self, _text: &str) -> anyhow::Result<()> {
        Ok(())
    }
}

/// Execute a test .star file with sha256 globals pre-loaded.
/// Returns the captured print output.
fn run_test_script(
    lib_path: &Path,
    test_path: &Path,
) -> Result<String, String> {
    let lib_content = fs::read_to_string(lib_path).map_err(|e| e.to_string())?;
    let test_content = fs::read_to_string(test_path).map_err(|e| e.to_string())?;

    // Concatenate lib + test into a single module to avoid multi-eval issues.
    let combined = format!("{}\n{}", lib_content, test_content);
    let ast = AstModule::parse(
        &test_path.to_string_lossy(),
        combined,
        &Dialect::Extended,
    )
    .map_err(|e| e.to_string())?;

    let globals = Globals::extended_by(&[LibraryExtension::Print]);
    let module = Module::new();

    let output: RefCell<Vec<String>> = RefCell::new(Vec::new());
    {
        let capture = CapturePrintHandler { lines: &output };
        let mut eval = Evaluator::new(&module);
        eval.set_print_handler(&capture);
        eval.eval_module(ast, &globals).map_err(|e| e.to_string())?;
    }

    Ok(output.into_inner().join("\n"))
}

struct TestResult {
    name: String,
    passed: bool,
    duration_secs: f64,
    failure: Option<String>,
}

fn find_runfiles_path(suffix: &str) -> PathBuf {
    for env_key in &["RUNFILES_DIR", "TEST_SRCDIR"] {
        if let Ok(base) = env::var(env_key) {
            let p = PathBuf::from(&base).join("_main").join(suffix);
            if p.exists() {
                return p;
            }
        }
    }
    panic!("cannot find {} in runfiles", suffix);
}

fn main() {
    let lib_path = find_runfiles_path("sha256.star");
    let testdata_dir = find_runfiles_path("tests/testdata");

    // Discover test pairs.
    let mut test_names: Vec<String> = fs::read_dir(&testdata_dir)
        .expect("cannot read testdata")
        .filter_map(|e| {
            let name = e.ok()?.file_name().into_string().ok()?;
            name.strip_suffix(".star").map(|s| s.to_owned())
        })
        .collect();
    test_names.sort();

    // Support --test_filter via TESTBRIDGE_TEST_ONLY.
    let filter = env::var("TESTBRIDGE_TEST_ONLY").unwrap_or_default();
    if !filter.is_empty() {
        test_names.retain(|n| n.contains(&filter));
    }

    let mut results: Vec<TestResult> = Vec::new();
    let mut passed = 0usize;
    let mut failed = 0usize;

    for name in &test_names {
        let star_file = testdata_dir.join(format!("{}.star", name));
        if !is_language_enabled(&star_file, "rust") {
            println!("SKIP: {}", name);
            continue;
        }
        let expected_file = testdata_dir.join(format!("{}.expected", name));

        let expected = match fs::read_to_string(&expected_file) {
            Ok(s) => s.trim_end().to_owned(),
            Err(_) => {
                eprintln!("FAIL: {} missing {}.expected", name, name);
                failed += 1;
                results.push(TestResult {
                    name: name.clone(),
                    passed: false,
                    duration_secs: 0.0,
                    failure: Some(format!("missing {}.expected", name)),
                });
                continue;
            }
        };

        let start = Instant::now();
        match run_test_script(&lib_path, &star_file) {
            Ok(actual) => {
                let secs = start.elapsed().as_secs_f64();
                let actual = actual.trim_end();
                if expected == actual {
                    println!("PASS: {}", name);
                    passed += 1;
                    results.push(TestResult {
                        name: name.clone(),
                        passed: true,
                        duration_secs: secs,
                        failure: None,
                    });
                } else {
                    let msg = format!("expected: {}\nactual:   {}", expected, actual);
                    eprintln!("FAIL: {}\n  {}", name, msg);
                    failed += 1;
                    results.push(TestResult {
                        name: name.clone(),
                        passed: false,
                        duration_secs: secs,
                        failure: Some(msg),
                    });
                }
            }
            Err(err) => {
                let secs = start.elapsed().as_secs_f64();
                if expected.starts_with("ERROR:") {
                    let expected_msg = expected["ERROR:".len()..].trim();
                    if err.contains(expected_msg) {
                        println!("PASS: {}", name);
                        passed += 1;
                        results.push(TestResult {
                            name: name.clone(),
                            passed: true,
                            duration_secs: secs,
                            failure: None,
                        });
                        continue;
                    }
                    let msg = format!(
                        "wrong error\n  expected: {}\n  actual:   {}",
                        expected_msg, err
                    );
                    eprintln!("FAIL: {} {}", name, msg);
                    failed += 1;
                    results.push(TestResult {
                        name: name.clone(),
                        passed: false,
                        duration_secs: secs,
                        failure: Some(msg),
                    });
                    continue;
                }
                let msg = format!("unexpected error: {}", err);
                eprintln!("FAIL: {} {}", name, msg);
                failed += 1;
                results.push(TestResult {
                    name: name.clone(),
                    passed: false,
                    duration_secs: secs,
                    failure: Some(msg),
                });
            }
        }
    }

    println!("\n{} passed, {} failed ({} total)", passed, failed, passed + failed);

    // Write JUnit XML if requested.
    if let Ok(xml_path) = env::var("XML_OUTPUT_FILE") {
        write_junit_xml(&xml_path, &results);
    }

    if failed > 0 {
        std::process::exit(1);
    }
}

fn write_junit_xml(path: &str, results: &[TestResult]) {
    let total_time: f64 = results.iter().map(|r| r.duration_secs).sum();
    let failures = results.iter().filter(|r| !r.passed).count();

    let mut xml = String::new();
    xml.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.push_str("<testsuites>\n");
    xml.push_str(&format!(
        "  <testsuite name=\"Sha256Test\" tests=\"{}\" failures=\"{}\" time=\"{:.3}\">\n",
        results.len(),
        failures,
        total_time
    ));

    for r in results {
        let name = escape_xml(&r.name);
        xml.push_str(&format!(
            "    <testcase name=\"{}\" classname=\"tests.rust.Sha256Test\" time=\"{:.3}\"",
            name, r.duration_secs
        ));
        if r.passed {
            xml.push_str("/>\n");
        } else {
            xml.push_str(">\n");
            let msg = r.failure.as_deref().unwrap_or("");
            let first_line = msg.lines().next().unwrap_or("");
            xml.push_str(&format!(
                "      <failure message=\"{}\">{}</failure>\n",
                escape_xml(first_line),
                escape_xml(msg)
            ));
            xml.push_str("    </testcase>\n");
        }
    }

    xml.push_str("  </testsuite>\n");
    xml.push_str("</testsuites>\n");

    if let Ok(mut f) = fs::File::create(path) {
        let _ = f.write_all(xml.as_bytes());
    }
}

/// Check if a test file has a `# languages: ...` marker on the first line.
/// If present, only the listed languages should run the test. If absent, all run it.
fn is_language_enabled(path: &Path, language: &str) -> bool {
    let Ok(content) = fs::read_to_string(path) else { return true };
    let first_line = content.lines().next().unwrap_or("");
    if let Some(langs) = first_line.strip_prefix("# languages:") {
        langs.split(',').any(|l| l.trim() == language)
    } else {
        true
    }
}

fn escape_xml(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}
