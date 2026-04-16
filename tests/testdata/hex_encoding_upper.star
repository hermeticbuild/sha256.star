def test():
    # Upper-case hex should work too
    print(sha256("68656C6C6F", encoding = "hex"))

test()
