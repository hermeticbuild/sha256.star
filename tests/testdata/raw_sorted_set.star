# languages: java, go
def test():
    # Iterate a set directly with raw=True.
    # Tests that sha256 accepts
    # any iterable of ints, not just lists.
    # set([72, 101, 108, 108, 111]) deduplicates to {72, 101, 108, 111}
    # in insertion order, which is the bytes for "Helo".
    print(sha256(set([72, 101, 108, 108, 111]), raw = True))

test()
