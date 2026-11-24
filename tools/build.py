#!/usr/bin/env python3
"""Offline Android build using official SDK build-tools + Eclipse Java compiler.
Standard Android Studio/Gradle builds are also supported by the project.
"""
import argparse, os, pathlib, shutil, subprocess, zipfile
p=argparse.ArgumentParser()
p.add_argument('--build-tools',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--ecj',required=True)
p.add_argument('--keystore',required=True);p.add_argument('--password-file',required=True);p.add_argument('--output',required=True)
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1];build=root/'build/manual';build.mkdir(parents=True,exist_ok=True)
bt=pathlib.Path(a.build_tools);jar=str(pathlib.Path(a.android_jar).resolve());app=root/'app/src/main'
def run(cmd):subprocess.run([str(x) for x in cmd],check=True,cwd=root)
for d in ['classes','gen','dex']:
    shutil.rmtree(build/d,ignore_errors=True);(build/d).mkdir()
run([bt/'aapt2','compile','--dir',app/'res','-o',build/'res.zip'])
run([bt/'aapt2','link','-o',build/'base.apk','-I',jar,'--manifest',app/'AndroidManifest.xml','--java',build/'gen','-A',app/'assets',build/'res.zip'])
sources=list((app/'java').rglob('*.java'))+list((build/'gen').rglob('*.java'))
boot=jar+os.pathsep+str(bt/'core-lambda-stubs.jar')
run(['java','-jar',a.ecj,'-source','1.8','-target','1.8','-nowarn','-encoding','UTF-8','-bootclasspath',boot,'-d',build/'classes',*sources])
with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
    for f in (build/'classes').rglob('*.class'):z.write(f,f.relative_to(build/'classes'))
run([bt/'d8','--min-api','30','--lib',jar,'--output',build/'dex',build/'classes.jar'])
shutil.copy2(build/'base.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    for f in (build/'dex').glob('*.dex'):z.write(f,f.name)
run([bt/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk'])
output=pathlib.Path(a.output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
run([bt/'apksigner','sign','--ks',a.keystore,'--ks-key-alias','kokolink','--ks-pass','file:'+a.password_file,'--out',output,build/'aligned.apk'])
run([bt/'apksigner','verify','--verbose','--print-certs',output])
run([bt/'zipalign','-c','4',output])
print('APK:',output,'bytes:',output.stat().st_size)
