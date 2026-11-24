package jp.haruyat.kokolink;
import org.json.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.lang.reflect.Field;

public class EngineTest {
 static int checks;
 static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;System.out.println("PASS "+msg);}
 static void await(BooleanSupplier check,String msg)throws Exception{long end=System.currentTimeMillis()+4000;while(!check.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(10);ok(check.getAsBoolean(),msg);}
 static JSONObject db(Engine e)throws Exception{Field f=Engine.class.getDeclaredField("db");f.setAccessible(true);return (JSONObject)f.get(e);}
 static JSONObject p(Engine e)throws Exception{return db(e).getJSONArray("peers").getJSONObject(0);}
 static void field(Engine e,String n,long value)throws Exception{Field f=Engine.class.getDeclaredField(n);f.setAccessible(true);f.setLong(e,value);}
 static JSONObject loc(long time)throws Exception{return new JSONObject().put("lat",34.07).put("lon",134.55).put("acc",80).put("time",time).put("battery",75).put("approx",false);}
 static void resetRequest(Engine a,Engine b)throws Exception{p(a).put("requestAt",0);p(b).put("answerAt",0);}
 public static void main(String[] args)throws Exception{
  try {
   KeyPair a=Protocol.keyPair(),b=Protocol.keyPair();String salt=Protocol.random(32),pair=Protocol.random(16);
   String ka=Protocol.derive(Protocol.b64(a.getPrivate().getEncoded()),Protocol.b64(b.getPublic().getEncoded()),salt,pair);
   String kb=Protocol.derive(Protocol.b64(b.getPrivate().getEncoded()),Protocol.b64(a.getPublic().getEncoded()),salt,pair);
   ok(ka.equals(kb),"ECDH / HKDF gives the same session key on both devices");
   ok(!ka.equals(salt),"Session key differs from invitation bearer secret");
   JSONObject payload=Protocol.message("request",Protocol.random(16),System.currentTimeMillis());
   String wire=Protocol.seal(ka,pair,payload);
   ok(Protocol.open(kb,pair,wire).getString("mid").equals(payload.getString("mid")),"AES-GCM round trip");
   ok(!wire.equals(Protocol.seal(ka,pair,payload)),"Fresh random nonce per packet");
   boolean rejected=false;try{Protocol.open(salt,pair,wire);}catch(Exception e){rejected=true;}ok(rejected,"Invitation secret cannot decrypt session traffic");
   JSONObject bad=new JSONObject(wire);byte[] bytes=Protocol.un64(bad.getString("c"));bytes[0]^=1;bad.put("c",Protocol.b64(bytes));rejected=false;try{Protocol.open(ka,pair,bad.toString());}catch(Exception e){rejected=true;}ok(rejected,"Tampered ciphertext rejected");
   rejected=false;try{Protocol.open(ka,Protocol.random(16),wire);}catch(Exception e){rejected=true;}ok(rejected,"Cross-pair packet rejected");
   ok(!Protocol.timely(payload,System.currentTimeMillis()+181000,180000),"Expired refresh request rejected");
   ok(!Protocol.validLocation(loc(System.currentTimeMillis()).put("lat",181),System.currentTimeMillis()),"Invalid coordinates rejected");
   ok(!Protocol.reusable(loc(System.currentTimeMillis()-301000),System.currentTimeMillis(),"eco"),"Old fix is never labelled reusable");
   ok(!Protocol.reusable(loc(System.currentTimeMillis()).put("acc",2000),System.currentTimeMillis(),"precise"),"Switching to precision does not reuse an imprecise fix");

   Engine alice=new Engine(new android.content.Context()),bob=new Engine(new android.content.Context());
   alice.configure("Alice","eco");bob.configure("Bob","eco");alice.visible(true);bob.visible(true);
   JSONObject invitation=alice.createInvite();String code=invitation.getString("code");
   rejected=false;try{Engine.parseInvite(code,System.currentTimeMillis()+16*60000);}catch(Exception e){rejected=true;}ok(rejected,"Expired invitations rejected");
   bob.join(code);
   await(()->alice.snapshot().optJSONArray("peers").length()==1,"Invite creates incoming approval request");
   ok("incoming".equals(p(alice).getString("status"))&&"outgoing".equals(p(bob).getString("status")),"Neither side active before approval");
   ok(p(alice).getString("code").equals(p(bob).getString("code")),"Both devices show the same verification digits");
   ok(!alice.snapshot().toString().contains(p(alice).getString("key")),"UI state contains no session key");
   String pid=p(alice).getString("id"),session=p(bob).getString("key");
   JSONObject request=Protocol.message("request",db(bob).getString("device"),System.currentTimeMillis());
   Relay.post(alice.topic(),Protocol.seal(session,pid,request));Thread.sleep(80);
   ok(alice.fixer.acquisitions==0,"Unapproved requester cannot activate location");
   alice.approve(pid);
   await(()->bob.snapshot().optJSONArray("peers").optJSONObject(0).optString("status").equals("active"),"Approval reaches invited device");
   ok(alice.fixer.acquisitions==0&&bob.fixer.acquisitions==0,"Pairing itself performs no location measurement");
   ok(db(alice).getJSONArray("invites").length()==0&&!p(bob).has("bootstrap"),"One-use bootstrap secrets removed after approval");
   Relay.Events obsoleteConnection=Relay.bus.get(bob.topic());
   bob.networkChanged();
   int beforeObsoleteMessage=bob.snapshot().optInt("received");
   JSONObject obsoleteMessage=Protocol.message("paused",db(alice).getString("device"),System.currentTimeMillis());
   obsoleteConnection.message(Protocol.seal(session,pid,obsoleteMessage));
   Thread.sleep(80);
   ok(bob.snapshot().optInt("received")==beforeObsoleteMessage,"Replaced relay cannot deliver queued messages");
   alice.request(pid,true);
   await(()->bob.snapshot().optInt("received")>=2,"Disabled device receives request");Thread.sleep(80);
   ok(bob.fixer.acquisitions==0,"Global sharing off blocks remote measurement");

   alice.enable(true);alice.service(true);bob.enable(true);bob.service(true);resetRequest(alice,bob);
   alice.request(pid,true);await(()->bob.fixer.acquisitions==1,"Opening request starts exactly one measurement");
   long t=System.currentTimeMillis()-15000;bob.fixer.complete(loc(t));
   await(()->alice.snapshot().optJSONArray("peers").optJSONObject(0).has("last"),"Encrypted reply updates peer location");
   ok(p(alice).getJSONObject("last").getLong("time")==t,"Original measurement timestamp is preserved");
   resetRequest(alice,bob);alice.request(pid,true);
   await(()->bob.snapshot().optInt("reused")>0,"Recent location reused for repeat request");Thread.sleep(80);
   ok(bob.fixer.acquisitions==1,"Cache reuse does not start GPS again");
   int traffic=Relay.traffic.size();for(int n=0;n<20;n++)alice.request(pid,true);Thread.sleep(80);
   ok(Relay.traffic.size()==traffic,"Rapid open/refresh taps do not flood the relay");
   int receives=bob.snapshot().optInt("received");String[] last=null;
   synchronized(Relay.traffic){for(String[] packet:Relay.traffic)if(packet[0].equals(bob.topic()))last=packet;}
   Relay.post(last[0],last[1]);Thread.sleep(80);ok(bob.snapshot().optInt("received")==receives,"Exact packet replay ignored");
   JSONObject stranger=Protocol.message("request",Protocol.random(16),System.currentTimeMillis());
   Relay.post(bob.topic(),Protocol.seal(session,pid,stranger));Thread.sleep(80);ok(bob.fixer.acquisitions==1,"Wrong sender identity is ignored even with valid envelope");

   bob.sharing(pid,false);resetRequest(alice,bob);alice.request(pid,true);Thread.sleep(80);ok(bob.fixer.acquisitions==1,"Per-person stop blocks measurement");
   bob.sharing(pid,true);db(bob).remove("own");field(bob,"lastSampleAt",-1);resetRequest(alice,bob);
   alice.request(pid,true);await(()->bob.fixer.acquisitions==2,"Second fresh measurement starts after cache cleared");
   bob.enable(false);bob.fixer.complete(loc(System.currentTimeMillis()));Thread.sleep(80);
   ok(p(alice).getJSONObject("last").getLong("time")==t,"Stopping during measurement cannot send a new location");
   bob.enable(true);bob.service(true);db(bob).remove("own");field(bob,"lastSampleAt",-1);resetRequest(alice,bob);
   alice.request(pid,true);await(()->bob.fixer.acquisitions==3,"Third measurement starts for revoke race test");
   alice.deletePeer(pid);await(()->bob.snapshot().optJSONArray("peers").optJSONObject(0).optString("status").equals("revoked"),"Revocation propagates to peer");
   bob.fixer.complete(loc(System.currentTimeMillis()));Thread.sleep(80);
   ok(alice.snapshot().getJSONArray("peers").length()==0,"Late response cannot restore deleted peer");
   bob.startPushTest();Thread.sleep(80);ok(bob.transport().equals("direct"),"Stream delivery alone cannot falsely verify external push");
   String testWire=null; synchronized(Relay.traffic){for(String[] packet:Relay.traffic)if(packet[0].equals(bob.topic())&&packet[1].contains("\"test\""))testWire=packet[1];}
   bob.external(bob.topic(),Relay.BASE,testWire);ok(bob.transport().equals("external"),"External broadcast round trip enables push mode");
   System.out.println("RESULT: "+checks+" checks passed; Android services/sensors are substituted in this JVM test.");
   System.exit(0);
  }catch(Throwable e){e.printStackTrace();System.exit(1);}
 }
}
