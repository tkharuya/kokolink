#!/usr/bin/env python3
"""Opt-in test: sends one encrypted synthetic message to a new random ntfy topic."""
import argparse,json,pathlib,secrets,subprocess,urllib.request
p=argparse.ArgumentParser();p.add_argument('--ecj',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=root/'build/relay-test';out.mkdir(parents=True,exist_ok=True)
subprocess.run(['java','-jar',a.ecj,'-source','17','-target','17','-nowarn','-cp',a.json_jar,'-d',str(out),str(root/'app/src/main/java/jp/haruyat/kokolink/Protocol.java'),str(root/'tests/ProtocolCli.java')],check=True)
cmd=['java','-cp',str(out)+':'+a.json_jar,'jp.haruyat.kokolink.ProtocolCli']
fixture=json.loads(subprocess.check_output(cmd,text=True));topic='kl_'+secrets.token_urlsafe(24)
headers={'User-Agent':'KokoLink/1.0 (integration test)','Content-Type':'text/plain; charset=utf-8','Title':'KokoLink','Priority':'2'}
req=urllib.request.Request('https://ntfy.sh/'+topic,data=fixture['wire'].encode(),headers=headers,method='POST')
with urllib.request.urlopen(req,timeout=25) as r:
 assert r.status==200
 posted=json.loads(r.read())
with urllib.request.urlopen(urllib.request.Request('https://ntfy.sh/'+topic+'/json?poll=1&since=10m',headers={'User-Agent':'KokoLink/1.0 (integration test)'}),timeout=25) as r:
 packets=[json.loads(line) for line in r.read().decode().splitlines() if line]
matches=[x for x in packets if x.get('event')=='message' and x.get('id')==posted['id']]
assert len(matches)==1,'Posted encrypted message not returned'
fixture['wire']=matches[0]['message']
subprocess.run(cmd+['decrypt'],input=json.dumps(fixture),text=True,check=True)
print('RESULT: 1 encrypted synthetic publish/retrieve/decrypt passed; no personal location transmitted.')
