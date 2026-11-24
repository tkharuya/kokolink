#!/usr/bin/env python3
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--ecj',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=root/'build/tests';out.mkdir(parents=True,exist_ok=True)
prod=root/'app/src/main/java/jp/haruyat/kokolink'
sources=[prod/'Protocol.java',prod/'Engine.java',*(root/'tests/jvm').rglob('*.java')]
subprocess.run(['java','-jar',a.ecj,'-source','17','-target','17','-nowarn','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*[str(s) for s in sources]],check=True)
subprocess.run(['java','-cp',str(out)+':'+a.json_jar,'jp.haruyat.kokolink.EngineTest'],check=True)
