def test():
    # keyword-only: passing output as positional should fail
    sha256("abc", "sri")

test()
