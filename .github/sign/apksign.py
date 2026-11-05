"""APK Signature Scheme v2 簽章（固定金鑰，讓之後的版本可以直接覆蓋安裝）。
用法：
  python3 apksign.py genkey KEYDIR              產生金鑰（只做一次，要備份）
  python3 apksign.py sign KEYDIR in.apk out.apk 用金鑰簽章
  python3 apksign.py verify KEYDIR out.apk      檢查簽章
"""
import sys, struct, hashlib, os, datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding

MAGIC = b'APK Sig Block 42'
V2_ID = 0x7109871a
ALG = 0x0103  # RSASSA-PKCS1-v1_5 + SHA2-256

def lp(b): return struct.pack('<I', len(b)) + b

def genkey(kd):
    os.makedirs(kd, exist_ok=True)
    if os.path.exists(os.path.join(kd, 'key.pem')): sys.exit('金鑰已經存在，不覆蓋')
    k = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    nm = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'SpeedCam TW'), x509.NameAttribute(NameOID.COUNTRY_NAME, 'TW')])
    now = datetime.datetime(2026, 1, 1)
    c = (x509.CertificateBuilder().subject_name(nm).issuer_name(nm).public_key(k.public_key())
         .serial_number(x509.random_serial_number()).not_valid_before(now)
         .not_valid_after(now + datetime.timedelta(days=365 * 40)).sign(k, hashes.SHA256()))
    open(os.path.join(kd, 'key.pem'), 'wb').write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    open(os.path.join(kd, 'cert.pem'), 'wb').write(c.public_bytes(serialization.Encoding.PEM))
    print('ok', c.fingerprint(hashes.SHA256()).hex())

def load(kd):
    k = serialization.load_pem_private_key(open(os.path.join(kd, 'key.pem'), 'rb').read(), None)
    c = x509.load_pem_x509_certificate(open(os.path.join(kd, 'cert.pem'), 'rb').read())
    return k, c

def split(d):
    """回傳 (zip 內容, 中央目錄, EOCD)，並去掉原本的簽章區塊"""
    e = d.rfind(b'PK\x05\x06')
    if e < 0 or len(d) - e > 65557: raise SystemExit('不是 zip')
    cd = struct.unpack('<I', d[e + 16:e + 20])[0]
    start = cd
    if d[cd - 16:cd] == MAGIC:
        sz = struct.unpack('<Q', d[cd - 24:cd - 16])[0]
        start = cd - sz - 8
        assert struct.unpack('<Q', d[start:start + 8])[0] == sz
    return d[:start], d[cd:e], d[e:]

def digest(sections):
    chunks = []
    for s in sections:
        for i in range(0, len(s), 1 << 20):
            c = s[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(c)) + c).digest())
    return hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()

def eocd_with(eocd, off):
    return eocd[:16] + struct.pack('<I', off) + eocd[20:]

def sign(kd, src, dst):
    k, c = load(kd)
    d = open(src, 'rb').read()
    body, cdir, eocd = split(d)
    dg = digest([body, cdir, eocd_with(eocd, len(body))])
    cert = c.public_bytes(serialization.Encoding.DER)
    signed = lp(lp(struct.pack('<I', ALG) + lp(dg))) + lp(lp(cert)) + lp(b'')
    sig = k.sign(signed, padding.PKCS1v15(), hashes.SHA256())
    pub = k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed) + lp(lp(struct.pack('<I', ALG) + lp(sig))) + lp(pub)
    v2 = lp(lp(signer))
    pair = struct.pack('<Q', len(v2) + 4) + struct.pack('<I', V2_ID) + v2
    size = len(pair) + 8 + 16
    block = struct.pack('<Q', size) + pair + struct.pack('<Q', size) + MAGIC
    out = body + block + cdir + eocd_with(eocd, len(body) + len(block))
    open(dst, 'wb').write(out)
    print('signed', dst, len(out))

def verify(kd, f):
    k, c = load(kd)
    d = open(f, 'rb').read()
    e = d.rfind(b'PK\x05\x06'); cd = struct.unpack('<I', d[e + 16:e + 20])[0]
    assert d[cd - 16:cd] == MAGIC, '沒有簽章區塊'
    sz = struct.unpack('<Q', d[cd - 24:cd - 16])[0]; start = cd - sz - 8
    p = start + 8; v2 = None
    while p < cd - 24:
        l = struct.unpack('<Q', d[p:p + 8])[0]; i = struct.unpack('<I', d[p + 8:p + 12])[0]
        if i == V2_ID: v2 = d[p + 12:p + 8 + l]
        p += 8 + l
    assert v2, '沒有 v2 簽章'
    def rd(b, o): n = struct.unpack('<I', b[o:o + 4])[0]; return b[o + 4:o + 4 + n], o + 4 + n
    signers, _ = rd(v2, 0); signer, _ = rd(signers, 0)
    signed, o = rd(signer, 0); sigs, o = rd(signer, o); pub, o = rd(signer, o)
    s1, _ = rd(sigs, 0); alg = struct.unpack('<I', s1[:4])[0]; sig, _ = rd(s1, 4)
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    load_der_public_key(pub).verify(sig, signed, padding.PKCS1v15(), hashes.SHA256())
    dgs, o2 = rd(signed, 0); dd, _ = rd(dgs, 0); want, _ = rd(dd, 4)
    got = digest([d[:start], d[cd:e], eocd_with(d[e:], start)])
    assert got == want, '內容雜湊不符'
    certs, _ = rd(signed, o2); cert, _ = rd(certs, 0)
    fp = hashlib.sha256(cert).hexdigest()
    mine = hashlib.sha256(c.public_bytes(serialization.Encoding.DER)).hexdigest()
    print('verify ok, alg %#x, cert %s, 是我們的金鑰: %s' % (alg, fp[:16], fp == mine))

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'genkey': genkey(sys.argv[2])
    elif cmd == 'sign': sign(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == 'verify': verify(sys.argv[2], sys.argv[3])
