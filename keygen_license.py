# -*- coding: utf-8 -*-
"""
行藏有度 注册码生成器/解密器 (与 APP License.java 共享密钥)
用法:
  python keygen_license.py gen <设备码> <天数>          # 生成: 从今天起N天有效
  python keygen_license.py gen <设备码> 2026-12-31     # 生成: 指定到期日
  python keygen_license.py dec <注册码>                # 解密: 查看绑定设备与到期时间
"""
import sys, datetime
from Crypto.Cipher import AES  # pip install pycryptodome

KEY = b"bM@2026!Lx#Yd$Kz"   # 与 License.java 的 KEY 一致
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def base32_encode(data: bytes) -> str:
    bitbuf, bits, out = 0, 0, []
    for b in data:
        bitbuf = (bitbuf << 8) | b
        bits += 8
        while bits >= 5:
            out.append(B32[(bitbuf >> (bits - 5)) & 31])
            bits -= 5
    if bits:
        out.append(B32[(bitbuf << (5 - bits)) & 31])
    return "".join(out)


def base32_decode(s: str) -> bytes:
    bitbuf, bits, out = 0, 0, []
    for ch in s.upper():
        v = B32.find(ch)
        if v < 0:
            raise ValueError("bad char " + ch)
        bitbuf = (bitbuf << 5) | v
        bits += 5
        if bits >= 8:
            out.append((bitbuf >> (bits - 8)) & 0xFF)
            bits -= 8
    return bytes(out)


def pad(b: bytes) -> bytes:
    n = 16 - len(b) % 16
    return b + bytes([n]) * n


def unpad(b: bytes) -> bytes:
    return b[:-b[-1]]


def encrypt(plain: str) -> str:
    c = AES.new(KEY, AES.MODE_ECB)
    return base32_encode(c.encrypt(pad(plain.encode())))


def decrypt(code: str) -> str:
    code = code.replace("-", "").replace(" ", "").upper()
    c = AES.new(KEY, AES.MODE_ECB)
    return unpad(c.decrypt(base32_decode(code))).decode()


def make_code(device_id: str, expire: str) -> str:
    # 明文: "1" + 设备码(8) + 到期yyMMdd(6) = 15字节(单AES块)
    raw = encrypt(f"1{device_id.upper()}{expire}")
    return "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def fmt_expire(exp: str) -> str:
    return datetime.datetime.strptime(exp, "%y%m%d").strftime("%Y-%m-%d") + " 23:59:59"


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "gen":
        dev, dur = sys.argv[2], sys.argv[3]
        if dur.isdigit() and len(dur) <= 5:   # 天数
            exp = datetime.datetime.now() + datetime.timedelta(days=int(dur))
            expire = exp.strftime("%y%m%d")
        else:                                  # 指定日期 YYYY-MM-DD
            expire = datetime.datetime.strptime(dur, "%Y-%m-%d").strftime("%y%m%d")
        code = make_code(dev, expire)
        print("注册码:", code)
        print("绑定设备:", dev.upper(), "| 到期:", fmt_expire(expire))
    elif cmd == "dec":
        info = decrypt(sys.argv[2])
        ver, dev, exp = info[0], info[1:9], info[9:]
        now = datetime.datetime.now().strftime("%y%m%d")
        expired = "已过期" if exp < now else "有效"
        print(f"版本: {ver} | 绑定设备: {dev} | 到期: {fmt_expire(exp)} [{expired}]")
    else:
        print(__doc__)
