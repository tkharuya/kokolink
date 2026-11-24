#!/usr/bin/env python3
"""Exercises shipped UI with a fake Native boundary; not an Android sensor test."""
import json,pathlib,threading,http.server,functools,time,base64,os
from playwright.sync_api import sync_playwright
root=pathlib.Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';out=root/'build/qa';out.mkdir(parents=True,exist_ok=True)
class Quiet(http.server.SimpleHTTPRequestHandler):
 def log_message(self,*a):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Quiet,directory=str(assets)))
threading.Thread(target=server.serve_forever,daemon=True).start()
url=f'http://127.0.0.1:{server.server_port}/index.html'
initial={'name':'テスト端末','enabled':False,'service':False,'mode':'eco','transport':'direct','peers':[],'events':[],'permission':False,'precisePermission':False,'status':'停止中','sent':0,'received':0,'samples':0,'reused':0}
mock='''window.testCalls=[];window.nativeState=INITIAL;window.Native={call(text){const m=JSON.parse(text);testCalls.push(m);let data=true;
switch(m.action){case 'state':data=nativeState;break;case 'settings':nativeState.name=m.name;nativeState.mode=m.mode;break;case 'enable':nativeState.enabled=true;nativeState.service=true;nativeState.permission=true;break;case 'disable':nativeState.enabled=false;nativeState.service=false;break;case 'invite':data={code:'KOKO1.'+'a'.repeat(600),expires:Date.now()+900000};break;case 'inspect':data={name:'テスト相手',expires:Date.now()+900000};break;case 'sharing':nativeState.peers.find(p=>p.id===m.id).sharing=m.value;break;case 'delete':nativeState.peers=nativeState.peers.filter(p=>p.id!==m.id);break;}
if(m.action!=='state')setTimeout(()=>window.renderNative(structuredClone(nativeState)),0);return JSON.stringify({ok:true,data});}};'''.replace('INITIAL',json.dumps(initial,ensure_ascii=False))
checks=0
def check(b,msg):
 global checks
 assert b,msg
 checks+=1;print('PASS',msg)
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True,executable_path=os.environ.get('BROWSER_EXECUTABLE','/usr/bin/chromium'),args=['--no-sandbox','--no-zygote','--single-process'])
 page=browser.new_page(viewport={'width':320,'height':740},device_scale_factor=1)
 errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.route('https://tile.openstreetmap.org/**',lambda r:r.fulfill(status=200,content_type='image/png',body=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=')))
 page.add_init_script(mock)
 for width,height in [(320,740),(393,852),(412,915),(840,1000)]:
  page.set_viewport_size({'width':width,'height':height});errors.clear()
  page.goto(url);page.wait_for_selector('#mapPeers .empty-people')
  for tab in ['map','people','power']:
   page.locator(f'[data-tab="{tab}"]').click()
   check(page.evaluate('document.documentElement.scrollWidth<=innerWidth'),f'{width}px {tab}: no horizontal overflow')
  page.locator('[data-mode="balanced"]').click();page.wait_for_function("nativeState.mode==='balanced'")
  check(page.locator('[data-mode="balanced"]').get_attribute('aria-pressed')=='true',f'{width}px mode selection updates UI')
  page.locator('[data-tab="map"]').click();page.locator('#toggleSharing').click()
  check(page.locator('#enableNow').is_disabled(),f'{width}px sharing requires explicit consent')
  page.locator('#setupName').fill('自分');page.locator('#setupConsent').check();page.locator('#enableNow').click()
  page.wait_for_function('nativeState.enabled===true')
  page.locator('#addTop').click();page.locator('#inviteName').fill('テスト端末');page.locator('#createInvite').click()
  check(page.locator('#qr img').count()==1,f'{width}px shareable invitation QR renders')
  check(page.evaluate('document.documentElement.scrollWidth<=innerWidth'),f'{width}px invitation modal fits')
  page.locator('#copyCode').click();check(page.evaluate("testCalls.some(c=>c.action==='copy'&&c.text.startsWith('kokolink://join#'))"),f'{width}px copy action sends deep link')
  page.locator('#closeModal').click()
  now=int(time.time()*1000)
  peer={'id':'testpeerid000000000000','name':'テスト相手','status':'active','sharing':True,'code':'1234 5678','last':{'lat':34.07,'lon':134.55,'acc':150,'time':now-720000,'battery':63},'result':'位置を受信しました'}
  page.evaluate('(p)=>{nativeState.peers=[p];window.renderNative(nativeState)}',peer);page.wait_for_timeout(150)
  check(page.locator('#mapPeers').get_by_text('過去の位置').count()==1,f'{width}px stale location visibly labelled')
  page.locator('#mapPeers [data-peer-action="details"]').click();check(page.locator('#modalBody').get_by_text('位置を測った日時').count()==1,f'{width}px measurement timestamp visible')
  page.locator('#closeModal').click()
  page.locator('[data-tab="power"]').click();page.locator('#externalSetup').click();check(page.locator('#testPush').count()==1,f'{width}px external push opt-in/test UI')
  page.locator('#closeModal').click();page.locator('[data-tab="map"]').click()
  if width==393:page.screenshot(path=str(out/'peer-phone.png'),full_page=True)
  page.evaluate("nativeState.peers=[];nativeState.enabled=false;nativeState.service=false;window.renderNative(nativeState)")
  if width==393:page.screenshot(path=str(out/'empty-phone.png'),full_page=True)
  if width==840:page.screenshot(path=str(out/'empty-fold.png'),full_page=True)
  # External names are treated as text, never HTML.
  peer['name']='<img src=x onerror=window.pwned=1>'
  page.evaluate('(p)=>{nativeState.peers=[p];window.renderNative(nativeState)}',peer)
  check(not page.evaluate('Boolean(window.pwned)'),f'{width}px untrusted name cannot execute HTML')
  check(not errors,f'{width}px no JavaScript runtime errors: {errors}')
 browser.close()
server.shutdown()
print(f'RESULT: {checks} UI checks passed. Tile images and Native API mocked; no GPS or Android lifecycle exercised.')
