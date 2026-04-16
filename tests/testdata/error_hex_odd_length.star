def test():
    # Odd-length hex string is invalid
    sha256("abc", encoding = "hex")

test()
