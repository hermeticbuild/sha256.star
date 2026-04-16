load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@sha256.bzl", "sha256")

write_file(
    name = "example",
    out = "input_file.sha256",
    content = [sha256("Hello, world!")],
)
