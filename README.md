# sha256.star

A pure [Starlark](https://github.com/bazelbuild/starlark) implementation of SHA-256.

No native extensions, no host calls. The single file `sha256.star`
(symlinked as `sha256.bzl`) can be
`load()`-ed from any Bazel rule, or used with a standalone Starlark interpreter.

The goal is **correctness**, not speed. A pure Starlark implementation will
always be orders of magnitude slower than vectorized native code. It targets
small inputs such as those hashed in a Bazel module extension to generate
lockfiles.

## Usage

### In a Bazel or Buck2 rule (`*.bzl`)

```starlark
load("@sha256.bzl", "sha256")

hash = sha256("hello")
# "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
```

For Bazel, add the dependency to your `MODULE.bazel`:

```starlark
bazel_dep(name = "sha256.bzl", version = "...")
```

### In standalone Starlark

```starlark
load("sha256.star", "sha256")

print(sha256("hello"))
```

### Interactive interpreter

A bundled interpreter reads from stdin with `sha256()` pre-loaded:

```sh
echo 'print(sha256("hello"))' | bazel run //interpreter
```

```sh
bazel run //interpreter < myscript.star
```

### sha256sum

A `sha256sum` replacement that computes hashes entirely in
Starlark:

```sh
echo -n hello | bazel run //interpreter:sha256sum
# 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824  -
```

## API

```starlark
sha256(input, *, encoding = "auto", output = "hex", raw = False)
```

### Parameters

| Parameter  | Type   | Default  | Description |
|------------|--------|----------|-------------|
| `input`    | string or iterable of int | *(required)* | Data to hash. |
| `encoding` | string | `"auto"` | How to interpret `input` (keyword-only). |
| `output`   | string | `"hex"`  | Output format (keyword-only). |
| `raw`      | bool   | `False`  | Stream mode (keyword-only). When `True`, `input` is iterated directly as byte values without materializing the full input in memory. The caller must ensure all elements are ints in 0x00–0xFF. |

### Input encoding

| `encoding` | Behavior |
|------------|----------|
| `"auto"`   | Strings are treated as raw bytes (each codepoint must be 0x00–0xFF). Iterables are treated as lists of byte-valued ints. |
| `"hex"`    | Input must be a string of hexadecimal characters (e.g. `"48656c6c6f"`). Upper and lower case are accepted. Length must be even. |

### Output format

| `output`  | Return type | Example |
|-----------|-------------|---------|
| `"hex"`   | `string`    | `"ba7816bf8f01cfea..."` (64 lowercase hex chars) |
| `"int"`   | `int`       | `84342368487090800...` (256-bit integer) |
| `"sri"`   | `string`    | `"sha256-ungWv48Bz+pBQU..."` (SRI hash) |
| `"byte_list"` | `list[int]` | `[186, 120, 22, 191, ...]` (32 ints, 0–255) |

### Examples

```starlark
# Raw string input (default)
sha256("abc")
# "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

# Byte list input
sha256([0x48, 0x65, 0x6c, 0x6c, 0x6f])
# "185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969"

# Hex-encoded input
sha256("deadbeef", encoding = "hex")

# SRI output (for Bazel's integrity attributes)
sha256("hello", output = "sri")
# "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="

# Raw byte list output
sha256("abc", output = "byte_list")
# [186, 120, 22, 191, 143, 1, 207, 234, ...]

# Streaming mode: hash an iterable without materializing it in memory
sha256(my_byte_iterator, raw = True)
```

## Testing

```sh
bazel test //tests/...
bazel build //example
buck2 build //example
```

The test suite is a battery of input–output pairs: each
`tests/testdata/<name>.star` file runs with `sha256` pre-loaded and its output
is compared against `tests/testdata/<name>.expected`. Add a test by dropping a new
`.star`/`.expected` pair into `tests/testdata/`.

The suite includes 129 official NIST CAVS byte-oriented test vectors
(`SHA256ShortMsg` and `SHA256LongMsg`) in addition to edge-case and
format-specific tests.

We test against three Starlark implementations:
- [Bazel](https://github.com/bazelbuild/bazel/blob/master/src/main/java/net/starlark/java/cmd/Main.java)
- [starlark-go](https://github.com/google/starlark-go)
- [starlark-rust](https://github.com/facebook/starlark-rust)
