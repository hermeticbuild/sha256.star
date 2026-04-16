# sha256.star

A repository for computing SHA-256 hashes in Bazel, hermetically.

Because modern computing depends, to a frankly unreasonable degree, on taking some bytes, hashing them, and then treating the resulting 64 hex characters as a statement of objective truth.

## Why SHA-256 Matters

SHA-256 is everywhere.

It is used to:

- identify files and artifacts
- verify downloads
- support content-addressed storage
- drive caches and build systems
- confirm that some bytes are, in fact, still those bytes

A lot of modern infrastructure works only because everyone agrees that, given the same input, a hash must always produce the same output.

This is a good system, and certainly not a fragile social contract holding civilization together.

## Why Hermeticity Matters

A SHA-256 pipeline that is not hermetic is just a checksum with ambitions.

If hashing depends on:

- whatever tools happen to be installed on the host
- platform-specific command behavior
- shell quirks
- locale, line endings, or other ambient nonsense

then it is no longer a dependable build primitive. It is a situation.

For something this fundamental, “seems fine on my machine” is not really the bar.

This repository exists to make SHA-256 computation explicit, reproducible, and independent of host-level weirdness.

## What This Repo Is About

`sha256.star` provides Bazel-oriented SHA-256 functionality for cases where reproducibility actually matters.

The core idea is simple:

> given the same bytes, produce the same digest, every time, everywhere.

Which is what a hash is supposed to do, and yet here we are having to make a whole repository about it.

## Design Principles

### Reproducibility

Same input, same output.

Missing this would be embarrassing.

### Hermeticity

Hashing should depend only on declared inputs, not on whatever your laptop has been through.

### Portability

Behavior should be consistent across local builds, remote execution, and different operating systems.

### Bazel-friendliness

This should behave like a proper part of the build graph, not a shell command that happened to work once.

## Why Not Just Use `sha256sum`?

Sometimes you can.

But if you want reliable behavior across platforms and environments, ambient host tools start looking less like infrastructure and more like folklore.

At that point, hashing is part of the build, and it should be treated that way.

## Goal

Compute SHA-256 in a way that is:

- correct
- reproducible
- hermetic
- usable from Bazel
- boring in the best possible way

## Closing Note

A surprising amount of software infrastructure depends on being able to say, with complete confidence, that some bytes are exactly the bytes we think they are.

`sha256.star` is for doing that hermetically, which is important, because non-hermetic hashing would be ridiculous.
