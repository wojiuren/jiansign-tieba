"""Small reproducible Android build, no Gradle or third-party app dependencies.
Set ANDROID_SDK_ROOT and JAVA_HOME to use other installed SDK/JDK locations.
Signing material is stored outside the delivered source tree.
"""
from pathlib import Path
import os, json, subprocess, zipfile, secrets, hashlib, tempfile, shutil

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'dist'
OUT.mkdir(exist_ok=True)
try:
    SDK = Path(os.environ['ANDROID_SDK_ROOT'])
    JDK = Path(os.environ['JAVA_HOME'])
except KeyError as error:
    raise SystemExit(f'Set {error.args[0]} before building.') from error
BT = SDK/'build-tools/36.0.0'
ANDROID = SDK/'platforms/android-37.0/android.jar'
WORK = Path(tempfile.mkdtemp(prefix='jiansign-build-'))
BUILD = WORK/'build'
for sub in ['', 'classes', 'generated', 'dex']:
    (BUILD/sub).mkdir(parents=True, exist_ok=True)

def run(args, env=None):
    subprocess.run([str(a) for a in args], check=True, env=env)

def signing():
    folder=Path.home()/'.jiansign-signing'
    folder.mkdir(exist_ok=True)
    cfg=folder/'local.json'
    if not cfg.exists():
        cfg.write_text(json.dumps({'password':secrets.token_urlsafe(32)}),encoding='utf-8')
    env=os.environ.copy()
    env['JIANSIGN_STORE_PASS']=json.loads(cfg.read_text(encoding='utf-8'))['password']
    keystore=folder/'release.p12'
    if not keystore.exists():
        run([JDK/'bin/keytool.exe','-genkeypair','-keystore',keystore,'-storetype','PKCS12','-alias','jiansign','-storepass:env','JIANSIGN_STORE_PASS','-keypass:env','JIANSIGN_STORE_PASS','-keyalg','RSA','-keysize','3072','-validity','36500','-dname','CN=JianSign Personal Build'],env)
    return keystore,env

def package(manifest,res,classes,dest,build):
    build.mkdir(exist_ok=True)
    resource_zip=build/'resources.zip'
    run([BT/'aapt2.exe','compile','--dir',res,'-o',resource_zip])
    unsigned=build/'unsigned.apk'
    run([BT/'aapt2.exe','link','-I',ANDROID,'--manifest',manifest,'--min-sdk-version','26','--target-sdk-version','35','-o',unsigned,resource_zip])
    dex=build/'dex';dex.mkdir(exist_ok=True)
    run([JDK/'bin/java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',ANDROID,'--output',dex,*classes])
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
        for p in dex.glob('*.dex'):z.write(p,p.name)
    aligned=build/'aligned.apk'
    run([BT/'zipalign.exe','-P','16','-f','4',unsigned,aligned])
    key,env=signing()
    run([JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','sign','--v4-signing-enabled','false','--ks',key,'--ks-key-alias','jiansign','--ks-pass','env:JIANSIGN_STORE_PASS','--key-pass','env:JIANSIGN_STORE_PASS','--out',dest,aligned],env)
    run([JDK/'bin/java.exe','-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',dest])
    run([BT/'zipalign.exe','-c','-P','16','4',dest])
    print('APK:',dest.name,'bytes:',dest.stat().st_size)

if __name__=='__main__':
    for name in ['src','res']:
        shutil.copytree(ROOT/name,WORK/name)
    shutil.copyfile(ROOT/'AndroidManifest.xml',WORK/'AndroidManifest.xml')
    sources=list((WORK/'src').rglob('*.java'))
    run([JDK/'bin/javac.exe','-encoding','UTF-8','--release','8','-classpath',ANDROID,'-d',BUILD/'classes',*sources])
    artifact=WORK/'jiansign-0.1.4.apk'
    package(WORK/'AndroidManifest.xml',WORK/'res',list((BUILD/'classes').rglob('*.class')),artifact,BUILD)
    dest=OUT/'简签贴吧-0.1.4.apk'
    shutil.copyfile(artifact,dest)
    (OUT/'verification').mkdir(exist_ok=True)
    (OUT/'verification'/'build-path.txt').write_text(str(WORK),encoding='utf-8')
    (OUT/'SHA256.txt').write_text(hashlib.sha256(dest.read_bytes()).hexdigest()+'  '+dest.name+'\n',encoding='utf-8')
