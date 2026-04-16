def test():
    # "hello" = 68656c6c6f in hex
    print(sha256("68656c6c6f", encoding = "hex"))

test()
