def test():
    print(sha256("hello"))
    print(sha256("hello", output = "sri"))
    print(len(sha256("hello", output = "byte_list")))

test()
