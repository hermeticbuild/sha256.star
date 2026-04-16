package sha256test

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"go.starlark.net/starlark"
)

// runTestScript loads sha256.star, executes the test script in the same
// global scope, and returns the captured print output.
func runTestScript(libPath, testPath string) (string, error) {
	var output strings.Builder

	thread := &starlark.Thread{
		Print: func(_ *starlark.Thread, msg string) {
			if output.Len() > 0 {
				output.WriteByte('\n')
			}
			output.WriteString(msg)
		},
	}

	// Load sha256.star and suppress prints during load.
	thread.Print = func(_ *starlark.Thread, _ string) {}
	globals, err := starlark.ExecFile(thread, libPath, nil, nil)
	if err != nil {
		return "", fmt.Errorf("loading sha256.star: %w", err)
	}

	// Re-enable print capture for the test.
	thread.Print = func(_ *starlark.Thread, msg string) {
		if output.Len() > 0 {
			output.WriteByte('\n')
		}
		output.WriteString(msg)
	}

	_, err = starlark.ExecFile(thread, testPath, nil, globals)
	if err != nil {
		return "", err
	}

	return output.String(), nil
}

func TestSha256(t *testing.T) {
	testdataDir := findRunfile(t, "tests/testdata")
	libPath := findRunfile(t, "sha256.star")

	entries, err := os.ReadDir(testdataDir)
	if err != nil {
		t.Fatalf("reading testdata: %v", err)
	}

	var testNames []string
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".star") {
			testNames = append(testNames, strings.TrimSuffix(e.Name(), ".star"))
		}
	}
	sort.Strings(testNames)

	if len(testNames) == 0 {
		t.Fatal("no *.star test files found in testdata")
	}

	for _, name := range testNames {
		t.Run(name, func(t *testing.T) {
			starFile := filepath.Join(testdataDir, name+".star")
			if !isLanguageEnabled(starFile, "go") {
				t.Skip("not enabled for go")
			}
			expectedFile := filepath.Join(testdataDir, name+".expected")

			expectedBytes, err := os.ReadFile(expectedFile)
			if err != nil {
				t.Fatalf("missing %s.expected", name)
			}
			expected := strings.TrimRight(string(expectedBytes), "\n\r ")

			actual, err := runTestScript(libPath, starFile)

			if err != nil {
				if strings.HasPrefix(expected, "ERROR:") {
					expectedMsg := strings.TrimSpace(expected[6:])
					if strings.Contains(err.Error(), expectedMsg) {
						return // pass
					}
					t.Fatalf("wrong error\n  expected: %s\n  actual:   %s", expectedMsg, err.Error())
				}
				t.Fatalf("unexpected error: %s", err.Error())
			}

			actual = strings.TrimRight(actual, "\n\r ")
			if expected != actual {
				t.Fatalf("expected: %s\nactual:   %s", expected, actual)
			}
		})
	}
}

func findRunfile(t *testing.T, suffix string) string {
	t.Helper()
	for _, base := range []string{
		os.Getenv("RUNFILES_DIR"),
		os.Getenv("TEST_SRCDIR"),
	} {
		if base == "" {
			continue
		}
		p := filepath.Join(base, "_main", suffix)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	t.Fatalf("cannot find %s in runfiles", suffix)
	return ""
}

// isLanguageEnabled checks if a test file has a "# languages: ..." marker on
// the first line. If present, only the listed languages should run the test.
// If absent, all languages run it.
func isLanguageEnabled(path, language string) bool {
	data, err := os.ReadFile(path)
	if err != nil {
		return true
	}
	firstLine, _, _ := strings.Cut(string(data), "\n")
	if !strings.HasPrefix(firstLine, "# languages:") {
		return true
	}
	langs := firstLine[len("# languages:"):]
	for _, l := range strings.Split(langs, ",") {
		if strings.TrimSpace(l) == language {
			return true
		}
	}
	return false
}
