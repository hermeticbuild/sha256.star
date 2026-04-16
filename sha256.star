# SHA-256 in pure Starlark.

_MASK32 = 0xFFFFFFFF

# Rust starlark's << operator wraps i32 to negative instead of promoting to
# BigInt when the result exceeds i32::MAX (2^31 - 1). For example,
# 128 << 24 yields -2147483648 rather than 2147483648. Subsequent >>
# operations then sign-extend instead of zero-filling, corrupting any
# bit-manipulation that passes through a negative intermediate.
_BROKEN_LSHIFT = (128 << 24) != 0x80000000

_K = [
    0x428a2f98,
    0x71374491,
    0xb5c0fbcf,
    0xe9b5dba5,
    0x3956c25b,
    0x59f111f1,
    0x923f82a4,
    0xab1c5ed5,
    0xd807aa98,
    0x12835b01,
    0x243185be,
    0x550c7dc3,
    0x72be5d74,
    0x80deb1fe,
    0x9bdc06a7,
    0xc19bf174,
    0xe49b69c1,
    0xefbe4786,
    0x0fc19dc6,
    0x240ca1cc,
    0x2de92c6f,
    0x4a7484aa,
    0x5cb0a9dc,
    0x76f988da,
    0x983e5152,
    0xa831c66d,
    0xb00327c8,
    0xbf597fc7,
    0xc6e00bf3,
    0xd5a79147,
    0x06ca6351,
    0x14292967,
    0x27b70a85,
    0x2e1b2138,
    0x4d2c6dfc,
    0x53380d13,
    0x650a7354,
    0x766a0abb,
    0x81c2c92e,
    0x92722c85,
    0xa2bfe8a1,
    0xa81a664b,
    0xc24b8b70,
    0xc76c51a3,
    0xd192e819,
    0xd6990624,
    0xf40e3585,
    0x106aa070,
    0x19a4c116,
    0x1e376c08,
    0x2748774c,
    0x34b0bcb5,
    0x391c0cb3,
    0x4ed8aa4a,
    0x5b9cca4f,
    0x682e6ff3,
    0x748f82ee,
    0x78a5636f,
    0x84c87814,
    0x8cc70208,
    0x90befffa,
    0xa4506ceb,
    0xbef9a3f7,
    0xc67178f2,
]

_H_INIT = [
    0x6a09e667,
    0xbb67ae85,
    0x3c6ef372,
    0xa54ff53a,
    0x510e527f,
    0x9b05688c,
    0x1f83d9ab,
    0x5be0cd19,
]

# Build a char-to-int lookup for string→bytes conversion.
# Starlark interpreters differ on string handling:
#   - Go starlark has elem_ords() which returns byte values directly.
#   - Java starlark only has elems() which returns single-char strings.
# We detect the implementation at load time and branch accordingly.
# The lookup table only covers ASCII (0-127) using portable octal escapes.
# For bytes > 127 in string input, use int-list input or elem_ords()-capable interpreters.
_HAS_ELEM_ORDS = hasattr("", "elem_ords")

def _build_char_to_int():
    if _HAS_ELEM_ORDS:
        return None  # not needed: we use elem_ords() at runtime
    table = {}
    ascii_bytes = (
        "\000\001\002\003\004\005\006\007\010\011\012\013\014\015\016\017" +
        "\020\021\022\023\024\025\026\027\030\031\032\033\034\035\036\037" +
        "\040\041\042\043\044\045\046\047\050\051\052\053\054\055\056\057" +
        "\060\061\062\063\064\065\066\067\070\071\072\073\074\075\076\077" +
        "\100\101\102\103\104\105\106\107\110\111\112\113\114\115\116\117" +
        "\120\121\122\123\124\125\126\127\130\131\132\133\134\135\136\137" +
        "\140\141\142\143\144\145\146\147\150\151\152\153\154\155\156\157" +
        "\160\161\162\163\164\165\166\167\170\171\172\173\174\175\176\177"
    )
    i = 0
    for c in ascii_bytes.elems():
        table[c] = i
        i += 1
    return table

_CHAR_TO_INT = _build_char_to_int()

_HEX_CHARS = "0123456789abcdef"

def _rotr(x, n):
    if _BROKEN_LSHIFT:
        x = x & _MASK32
    return ((x >> n) | (x << (32 - n))) & _MASK32

def _sigma0(x):
    if _BROKEN_LSHIFT:
        x = x & _MASK32
    return _rotr(x, 7) ^ _rotr(x, 18) ^ (x >> 3)

def _sigma1(x):
    if _BROKEN_LSHIFT:
        x = x & _MASK32
    return _rotr(x, 17) ^ _rotr(x, 19) ^ (x >> 10)

def _capsigma0(x):
    return _rotr(x, 2) ^ _rotr(x, 13) ^ _rotr(x, 22)

def _capsigma1(x):
    return _rotr(x, 6) ^ _rotr(x, 11) ^ _rotr(x, 25)

def _ch(x, y, z):
    return (x & y) ^ ((x ^ _MASK32) & z)

def _maj(x, y, z):
    return (x & y) ^ (x & z) ^ (y & z)

def _from_bytes(byte_list):
    result = 0
    for b in byte_list:
        result = (result << 8) | b
    if _BROKEN_LSHIFT:
        result = result & _MASK32
    return result

def _to_bytes(val, n):
    result = []
    for _ in range(n):
        result.append(val & 0xFF)
        val = val >> 8
    return list(reversed(result))

def _hex8(val):
    chars = []
    for _ in range(8):
        chars.append(_HEX_CHARS[val & 0xF])
        val = val >> 4
    return "".join(reversed(chars))

def _build_hex_to_int():
    table = {}
    for i in range(10):
        table[_HEX_CHARS[i]] = i
    for i in range(6):
        table[_HEX_CHARS[10 + i]] = 10 + i
        table["ABCDEF"[i]] = 10 + i
    return table

_HEX_TO_INT = _build_hex_to_int()

def _hex_decode(s):
    if len(s) % 2 != 0:
        fail("sha256: hex string must have even length, got %d" % len(s))
    result = []
    hi_val = -1
    for c in s.elems():
        v = _HEX_TO_INT.get(c)
        if v == None:
            fail("sha256: invalid hex character in input")
        if hi_val < 0:
            hi_val = v
        else:
            result.append(hi_val * 16 + v)
            hi_val = -1
    return result

def _input_to_bytes(input, encoding):
    if encoding == "hex":
        if type(input) != "string":
            fail("sha256: encoding='hex' requires a string input")
        return _hex_decode(input)
    if type(input) == "string":
        if _HAS_ELEM_ORDS:
            result = list(input.elem_ords())
            for b in result:
                if b > 255:
                    fail("sha256: string contains codepoint outside 0x00-0xFF range")
            return result
        else:
            result = []
            for c in input.elems():
                v = _CHAR_TO_INT.get(c)
                if v == None:
                    fail("sha256: string contains codepoint outside 0x00-0xFF range")
                result.append(v)
            return result
    else:
        result = []
        for b in input:
            if type(b) != "int" or b < 0 or b > 255:
                fail("sha256: iterable must contain ints in range 0x00-0xFF, got %s" % repr(b))
            result.append(b)
        return result

def _pad(msg):
    length_bits = len(msg) * 8

    # Padding: append 0x80, then zeros, then 8-byte big-endian length.
    # Total padded length must be a multiple of 64 bytes.
    # zero_pad_count = (55 - len(msg) % 64) % 64
    pad_zeros = (55 - len(msg) % 64) % 64
    msg = msg + [0x80] + [0x00] * pad_zeros + _to_bytes(length_bits, 8)
    return msg

def _base64_encode(byte_list):
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    result = []
    n = len(byte_list)
    for i in range(0, n, 3):
        b0 = byte_list[i]
        b1 = byte_list[i + 1] if i + 1 < n else 0
        b2 = byte_list[i + 2] if i + 2 < n else 0
        triple = (b0 << 16) | (b1 << 8) | b2
        result.append(alphabet[(triple >> 18) & 0x3F])
        result.append(alphabet[(triple >> 12) & 0x3F])
        if i + 1 < n:
            result.append(alphabet[(triple >> 6) & 0x3F])
        else:
            result.append("=")
        if i + 2 < n:
            result.append(alphabet[triple & 0x3F])
        else:
            result.append("=")
    return "".join(result)

def _compress(h, block):
    """Run SHA-256 compression on a single 64-byte block, updating h in place."""
    w = []
    for t in range(16):
        w.append(_from_bytes(block[t * 4:(t * 4) + 4]))
    for t in range(16, 64):
        w.append((_sigma1(w[t - 2]) + w[t - 7] + _sigma0(w[t - 15]) + w[t - 16]) & _MASK32)

    a = h[0]
    b = h[1]
    c = h[2]
    d = h[3]
    e = h[4]
    f = h[5]
    g = h[6]
    hh = h[7]

    for t in range(64):
        t1 = (hh + _capsigma1(e) + _ch(e, f, g) + _K[t] + w[t]) & _MASK32
        t2 = (_capsigma0(a) + _maj(a, b, c)) & _MASK32
        hh = g
        g = f
        f = e
        e = (d + t1) & _MASK32
        d = c
        c = b
        b = a
        a = (t1 + t2) & _MASK32

    h[0] = (h[0] + a) & _MASK32
    h[1] = (h[1] + b) & _MASK32
    h[2] = (h[2] + c) & _MASK32
    h[3] = (h[3] + d) & _MASK32
    h[4] = (h[4] + e) & _MASK32
    h[5] = (h[5] + f) & _MASK32
    h[6] = (h[6] + g) & _MASK32
    h[7] = (h[7] + hh) & _MASK32

def _format_output(h, output):
    if output == "hex":
        return "".join([_hex8(v) for v in h])
    elif output == "int":
        result = 0
        for v in h:
            result = (result << 32) | v
        return result
    elif output == "byte_list":
        result = []
        for v in h:
            result.extend(_to_bytes(v, 4))
        return result
    elif output == "sri":
        byte_list = []
        for v in h:
            byte_list.extend(_to_bytes(v, 4))
        return "sha256-" + _base64_encode(byte_list)
    fail("sha256: unexpected output format: %s" % repr(output))

def _sha256_stream(iterable, output):
    """Hash a byte iterable without ever holding the full input in memory.

    Consumes the iterable 64 bytes at a time, compressing each full block
    immediately. Only a single 64-byte buffer is live at any point.
    """
    h = list(_H_INIT)
    buf = []
    total = 0

    for byte_val in iterable:
        buf.append(byte_val)
        if len(buf) == 64:
            _compress(h, buf)
            buf = []
        total += 1

    # buf now holds the final 0-63 bytes (not yet compressed).
    # Pad: append 0x80, then zeros so (len + padding) % 64 == 56, then 8-byte length.
    length_bits = total * 8
    buf.append(0x80)

    # If buf is now > 56 bytes, we need to fill this block and add another.
    pad_target = 56
    if len(buf) > 56:
        pad_target = 56 + 64
    buf.extend([0x00] * (pad_target - len(buf)))
    buf.extend(_to_bytes(length_bits, 8))

    # Compress remaining 1 or 2 blocks.
    for i in range(0, len(buf), 64):
        _compress(h, buf[i:i + 64])

    return _format_output(h, output)

def sha256(input, *, encoding = "auto", output = "hex", raw = False):
    """Compute SHA-256 hash.

    Args:
        input: string with codepoints in 0x00-0xFF, or iterable of ints in 0x00-0xFF.
        encoding: "auto" (default) treats strings as raw bytes and iterables as byte lists.
                  "hex" decodes the input string as hexadecimal (e.g. "0a1b23").
        output: "hex" for lowercase hex string, "int" for a single integer,
                  "sri" for SRI format, "byte_list" for list of ints.
        raw: if True, skip input validation and iterate the input directly as byte values
             without ever materializing the full input. The caller must ensure all elements
             are ints in 0x00-0xFF.

    Returns:
        Hash in the specified output format.
    """
    if encoding not in ("auto", "hex"):
        fail("sha256: encoding must be 'auto' or 'hex', got %s" % repr(encoding))
    if output not in ("hex", "int", "sri", "byte_list"):
        fail("sha256: output must be 'hex', 'int', 'sri', or 'byte_list', got %s" % repr(output))

    if raw:
        return _sha256_stream(input, output)

    msg = _input_to_bytes(input, encoding)
    msg = _pad(msg)

    h = list(_H_INIT)

    for block_idx in range(0, len(msg), 64):
        _compress(h, msg[block_idx:block_idx + 64])

    return _format_output(h, output)
