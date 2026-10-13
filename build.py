"""Offline build with the official Android SDK and JDK; no third-party dependencies."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def run(*args):
    args = [str(a) for a in args]
    print('> ' + Path(args[0]).name + ' ' + ' '.join(args[1:]), flush=True)
    subprocess.run(args, check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser()
    adb = shutil.which('adb')
    default_sdk = Path(adb).parent.parent if adb else Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', str(default_sdk)))
    parser.add_argument('--java', default=os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
    args = parser.parse_args()
    sdk, java = Path(args.sdk), Path(args.java) / 'bin'
    tools = sdk / 'build-tools' / '36.0.0'
    android = sdk / 'platforms' / 'android-36' / 'android.jar'
    build = ROOT / 'build'
    if build.exists():
        if build.resolve().parent != ROOT.resolve():
            raise RuntimeError('Unexpected build path')
        shutil.rmtree(build)
    for name in ['classes', 'generated', 'dex', 'test-classes', 'test-output']:
        (build / name).mkdir(parents=True, exist_ok=True)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    source = ROOT / 'app' / 'src' / 'main'
    run(tools / 'aapt2.exe', 'compile', '--dir', source / 'res', '-o', build / 'resources.zip')
    run(tools / 'aapt2.exe', 'link', '-o', build / 'base.apk', '-I', android,
        '--manifest', source / 'AndroidManifest.xml', '--java', build / 'generated', build / 'resources.zip')
    files = list((source / 'java').rglob('*.java')) + list((build / 'generated').rglob('*.java'))
    sources = build / 'sources.txt'
    sources.write_text('\n'.join('"' + str(f).replace('\\', '/') + '"' for f in files), encoding='utf-8')
    bootclasspath = str(android) + os.pathsep + str(tools / 'core-lambda-stubs.jar')
    run(java / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', bootclasspath, '-d', build / 'classes', '@' + str(sources))
    run(java / 'jar.exe', 'cf', build / 'classes.jar', '-C', build / 'classes', '.')
    run(java / 'java.exe', '-cp', tools / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', android, '--output', build / 'dex', build / 'classes.jar')
    shutil.copyfile(build / 'base.apk', build / 'unsigned.apk')
    with zipfile.ZipFile(build / 'unsigned.apk', 'a') as apk:
        for dex in (build / 'dex').glob('*.dex'):
            apk.write(dex, dex.name)
        apk.write(ROOT / 'NOTICE.txt', 'assets/NOTICE.txt')
        apk.write(ROOT / 'licenses' / 'Apache-2.0.txt', 'assets/licenses/Apache-2.0.txt')
    run(tools / 'zipalign.exe', '-f', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk')
    signing = ROOT / 'signing'
    signing.mkdir(exist_ok=True)
    key = signing / 'local-development.jks'
    if not key.exists():
        run(java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'kol', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Kol Local Development')
    version = ET.parse(source / 'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    apk = dist / f'Kol-Recorder-{version}.apk'
    run(java / 'java.exe', '-jar', tools / 'lib' / 'apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'kol', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, build / 'aligned.apk')
    run(java / 'java.exe', '-jar', tools / 'lib' / 'apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
    run(tools / 'zipalign.exe', '-c', '-v', '4', apk)
    run(java / 'javac.exe', '-encoding', 'UTF-8', '-d', build / 'test-classes', source / 'java' / 'il' / 'co' / 'kolrecorder' / 'WavFile.java', ROOT / 'tests' / 'WavFileTest.java')
    run(java / 'java.exe', '-cp', build / 'test-classes', 'WavFileTest', build / 'test-output')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist / 'SHA256SUMS.txt').write_text(f'{digest}  {apk.name}\n', encoding='utf-8')
    print(f'APK: {apk}\nSHA256: {digest}')


if __name__ == '__main__':
    main()
