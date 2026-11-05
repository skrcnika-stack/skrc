"""JAR（v1）簽章：Android 5.x、6.x 只認這種簽章，沒有它就裝不起來。
用法：python3 v1sign.py KEYDIR in.apk out.apk
做法：不動原本的檔案位置（resources.arsc 的對齊不會壞），只在 zip 最後加上 META-INF 三個檔。
之後再跑 apksign.py sign 加 v2 簽章。"""
import sys,zipfile,hashlib,base64,shutil,os
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import apksign as A
from cryptography.hazmat.primitives.serialization import pkcs7, Encoding
from cryptography.hazmat.primitives import hashes
kd,src,dst=sys.argv[1:4]
def b64(b): return base64.b64encode(hashlib.sha256(b).digest()).decode()
def wrap(line):  # 每行最多 72 bytes，超過用「空白開頭」接下一行
    b=line.encode('utf-8'); out=[b[:72]]; b=b[72:]
    while b: out.append(b' '+b[:71]); b=b[71:]
    return b'\r\n'.join(out)+b'\r\n'
z=zipfile.ZipFile(src)
names=[n for n in z.namelist() if not n.endswith('/')]
bad=[n for n in names if n.upper().startswith('META-INF/') and (n.upper().endswith(('.SF','.RSA','.DSA','.EC')) or n.upper()=='META-INF/MANIFEST.MF')]
if bad: sys.exit('來源 APK 已經有 v1 簽章檔，請用未簽章的 release APK：%s'%bad)
man=b'Manifest-Version: 1.0\r\nCreated-By: 1.0 (SpeedCam)\r\n\r\n'
sf_entries=b''
for n in names:
    sec=wrap('Name: '+n)+wrap('SHA-256-Digest: '+b64(z.read(n)))+b'\r\n'
    man+=sec
    sf_entries+=wrap('Name: '+n)+wrap('SHA-256-Digest: '+b64(sec))+b'\r\n'
z.close()
main=b'Manifest-Version: 1.0\r\nCreated-By: 1.0 (SpeedCam)\r\n\r\n'
sf=(b'Signature-Version: 1.0\r\nCreated-By: 1.0 (SpeedCam)\r\n'+wrap('SHA-256-Digest-Manifest: '+b64(man))
    +wrap('SHA-256-Digest-Manifest-Main-Attributes: '+b64(main))+b'X-Android-APK-Signed: 2\r\n\r\n'+sf_entries)
k,c=A.load(kd)
rsa=pkcs7.PKCS7SignatureBuilder().set_data(sf).add_signer(c,k,hashes.SHA256()).sign(Encoding.DER,[pkcs7.PKCS7Options.DetachedSignature,pkcs7.PKCS7Options.NoCapabilities])
shutil.copy(src,dst)
with zipfile.ZipFile(dst,'a',compression=zipfile.ZIP_DEFLATED) as o:
    for n,d in [('META-INF/MANIFEST.MF',man),('META-INF/SPEEDCAM.SF',sf),('META-INF/SPEEDCAM.RSA',rsa)]:
        o.writestr(zipfile.ZipInfo(n,date_time=(1981,1,1,1,1,2)),d,compress_type=zipfile.ZIP_DEFLATED)
print('v1 signed',dst,len(names),'files')
