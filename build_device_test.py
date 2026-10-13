"""Build a separate signed instrumentation APK for the connected-device smoke test."""
from pathlib import Path
import os
import shutil
import zipfile
from build import ROOT, run

sdk = Path(shutil.which('adb')).parent.parent
tools = sdk / 'build-tools' / '36.0.0'
android = sdk / 'platforms' / 'android-36' / 'android.jar'
java = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr')) / 'bin'
test = ROOT / 'build' / 'device-test'
(test / 'classes').mkdir(parents=True, exist_ok=True)
(test / 'dex').mkdir(exist_ok=True)
run(tools / 'aapt2.exe', 'link', '-I', android, '--manifest', ROOT / 'tests' / 'device' / 'AndroidManifest.xml', '-o', test / 'base.apk')
run(java / 'javac.exe', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', android,
    '-classpath', ROOT / 'build' / 'classes', '-d', test / 'classes', *sorted((ROOT / 'tests' / 'device').glob('*.java')))
run(java / 'jar.exe', 'cf', test / 'classes.jar', '-C', test / 'classes', '.')
run(java / 'java.exe', '-cp', tools / 'lib' / 'd8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android, '--classpath', ROOT / 'build' / 'classes.jar', '--output', test / 'dex', test / 'classes.jar')
with zipfile.ZipFile(test / 'base.apk', 'a') as apk:
    apk.write(test / 'dex' / 'classes.dex', 'classes.dex')
run(tools / 'zipalign.exe', '-f', '4', test / 'base.apk', test / 'aligned.apk')
run(java / 'java.exe', '-jar', tools / 'lib' / 'apksigner.jar', 'sign', '--ks', ROOT / 'signing' / 'local-development.jks', '--ks-key-alias', 'kol', '--ks-pass', 'pass:android', '--out', test / 'test.apk', test / 'aligned.apk')
print(test / 'test.apk')
