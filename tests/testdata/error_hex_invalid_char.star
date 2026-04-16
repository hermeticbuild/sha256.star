def test():
    # Invalid hex character
    sha256("zz", encoding = "hex")

test()
