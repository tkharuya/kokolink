package jp.haruyat.kokolink;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.*;

/** Owns consent, pairing, encrypted messaging, request coalescing, and saved state. */
public final class Engine {
    public interface Listener { void changed(); }
    final Context ctx;
    final Fixer fixer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService sender=Executors.newSingleThreadExecutor();
    private final ExecutorService receiver=Executors.newSingleThreadExecutor();
    private Relay relay;
    private Vault vault;
    private JSONObject db;
    private Listener listener;
    private boolean visible, serviceActive, connected, measuring;
    private String status="停止中", error="", fatal="";
    private String pushTest="";
    private long pushTestUntil;
    private long lastSampleAt=-1;
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();

    Engine(Context ctx) {
        this.ctx=ctx; fixer=new Fixer(ctx);
        try {
            vault=new Vault(ctx); db=vault.read();
            if(db==null) {
                db=new JSONObject().put("version",1).put("device",Protocol.random(16)).put("topic","kl_"+Protocol.random(24))
                    .put("name","自分").put("enabled",false).put("mode","eco").put("transport","direct")
                    .put("peers",new JSONArray()).put("invites",new JSONArray()).put("events",new JSONArray())
                    .put("sent",0).put("received",0).put("samples",0).put("reused",0);
                vault.write(db);
            }
            JSONObject ids=db.optJSONObject("seen");
            if(ids!=null) { Iterator<String> it=ids.keys();while(it.hasNext()){String k=it.next();seen.put(k,ids.optLong(k));} }
        } catch(Exception e) { fatal="保存データを開けません。再インストール前に、必要な招待情報を確認してください。"; db=new JSONObject(); }
    }
    synchronized void setListener(Listener l) { listener=l; }
    private void changed() { Listener l=listener;if(l!=null)main.post(l::changed); }
    synchronized void save() {
        try { if(!fatal.isEmpty())return;vault.write(db); }
        catch(Exception e) { error="端末への保存に失敗しました。空き容量を確認してください"; }
        changed();
    }
    synchronized boolean enabled() { return fatal.isEmpty()&&db.optBoolean("enabled"); }
    synchronized boolean serviceActive() { return serviceActive; }
    synchronized String topic() { return db.optString("topic"); }
    synchronized String transport() { return db.optString("transport","direct"); }
    synchronized void visible(boolean show) {
        if(show&&!visible&&!connected&&relay!=null){relay.stop();relay=null;}
        visible=show;connectionNeeded();changed();
    }
    synchronized void networkChanged() { if(relay!=null){relay.stop();relay=null;connected=false;}connectionNeeded();changed(); }
    synchronized void service(boolean running) { serviceActive=running;connectionNeeded();changed(); }
    synchronized void error(String value) { error=value;changed(); }
    synchronized void enable(boolean yes) throws Exception {
        if(!fatal.isEmpty())throw new IllegalStateException(fatal);
        db.put("enabled",yes);
        if(!yes) { fixer.cancel();measuring=false;for(JSONObject p:peers())if("active".equals(p.optString("status")))send(p,base("paused"),false); }
        save();connectionNeeded();
    }
    private void connectionNeeded() {
        boolean need=fatal.isEmpty()&&(visible||(enabled()&&serviceActive&&"direct".equals(transport())));
        if(need&&relay==null) {
            relay=new Relay();status="接続中";
            Relay selected=relay;
            relay.start(topic(),new Relay.Events(){
                public void connected(boolean ok,String err) { synchronized(Engine.this){if(relay!=selected)return;connected=ok;status=ok?"受信待機中":err;changed();} }
                public void message(String wire) {
                    receiver.execute(()->{
                        synchronized(Engine.this) {
                            if(relay==selected)receive(wire);
                        }
                    });
                }
            });
        } else if(!need&&relay!=null) { relay.stop();relay=null;connected=false; }
        if(!need)status=enabled()&&serviceActive&&"external".equals(transport())?"外部プッシュ待機中":"停止中";
    }
    private JSONArray array(String key) { JSONArray a=db.optJSONArray(key);return a==null?new JSONArray():a; }
    private ArrayList<JSONObject> peers() { ArrayList<JSONObject> p=new ArrayList<>();JSONArray a=array("peers");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)p.add(o);}return p; }
    private JSONObject peer(String id) { for(JSONObject p:peers())if(id.equals(p.optString("id")))return p;return null; }
    private JSONObject invite(String id) { JSONArray a=array("invites");for(int i=0;i<a.length();i++)if(id.equals(a.optJSONObject(i).optString("id")))return a.optJSONObject(i);return null; }
    private void remove(String array,String id) throws Exception { JSONArray out=new JSONArray();JSONArray a=array(array);for(int i=0;i<a.length();i++)if(!id.equals(a.optJSONObject(i).optString("id")))out.put(a.get(i));db.put(array,out); }
    private JSONObject base(String type) throws Exception { return Protocol.message(type,db.getString("device"),System.currentTimeMillis()); }
    private void event(String text) {
        try { JSONArray out=new JSONArray();out.put(new JSONObject().put("time",System.currentTimeMillis()).put("text",text));JSONArray a=array("events");for(int i=0;i<Math.min(29,a.length());i++)out.put(a.get(i));db.put("events",out); }catch(Exception ignored){}
    }
    synchronized JSONObject snapshot() {
        JSONObject out=new JSONObject();
        try {
            out.put("name",db.optString("name","自分")).put("enabled",enabled()).put("service",serviceActive)
                .put("mode",db.optString("mode","eco")).put("transport",transport()).put("connected",connected).put("status",status)
                .put("error",error).put("fatal",fatal).put("permission",fixer.permitted()).put("precisePermission",fixer.precise())
                .put("measuring",measuring).put("own",db.optJSONObject("own")).put("events",array("events"))
                .put("sent",db.optInt("sent")).put("received",db.optInt("received")).put("samples",db.optInt("samples")).put("reused",db.optInt("reused"))
                .put("externalVerified",db.optLong("externalVerified")).put("pushTestWaiting",!pushTest.isEmpty()&&System.currentTimeMillis()<pushTestUntil)
                .put("batteryUnrestricted",((PowerManager)ctx.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(ctx.getPackageName()));
            JSONArray list=new JSONArray();
            for(JSONObject p:peers()) {
                JSONObject v=new JSONObject();
                for(String k:Arrays.asList("id","name","status","sharing","code","last","requestAt","result","resultAt","lastSent","approvedAt","remotePaused"))if(p.has(k))v.put(k,p.get(k));
                list.put(v);
            }
            out.put("peers",list);
        }catch(Exception ignored){}
        return out;
    }
    synchronized JSONObject createInvite() throws Exception {
        requireReady();
        KeyPair kp=Protocol.keyPair();String id=Protocol.random(16);long expires=System.currentTimeMillis()+15*60_000;
        JSONObject i=new JSONObject().put("id",id).put("key",Protocol.random(32)).put("priv",Protocol.b64(kp.getPrivate().getEncoded()))
            .put("pub",Protocol.b64(kp.getPublic().getEncoded())).put("expires",expires);
        JSONArray a=new JSONArray();for(int n=0;n<array("invites").length();n++){JSONObject old=array("invites").getJSONObject(n);if(old.optLong("expires")>System.currentTimeMillis())a.put(old);}a.put(i);db.put("invites",a);save();
        JSONObject publicInvite=new JSONObject().put("v",1).put("pair",id).put("key",i.getString("key")).put("pub",i.getString("pub"))
            .put("from",db.getString("device")).put("topic",topic()).put("name",db.getString("name")).put("expires",expires);
        String code="KOKO1."+Protocol.b64(publicInvite.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new JSONObject().put("code",code).put("expires",expires);
    }
    static JSONObject parseInvite(String text,long now) throws Exception {
        if(text==null||text.length()>12_000)throw new IllegalArgumentException("招待コードが不正です");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("KOKO1\\.([A-Za-z0-9_-]+)").matcher(text);
        if(!m.find())throw new IllegalArgumentException("KOKO1. から始まる招待コードを貼り付けてください");
        JSONObject i=new JSONObject(new String(Protocol.un64(m.group(1)),java.nio.charset.StandardCharsets.UTF_8));
        if(i.optInt("v")!=1||!Protocol.id(i.optString("pair"))||!Protocol.id(i.optString("from"))||!Protocol.topic(i.optString("topic"))||Protocol.un64(i.getString("key")).length!=32)throw new IllegalArgumentException("招待コードが不正です");
        if(i.optLong("expires")<now||i.optLong("expires")>now+16*60_000)throw new IllegalArgumentException("招待の有効期限が切れています。作り直してください");
        return i;
    }
    synchronized JSONObject inspectInvite(String text) throws Exception { JSONObject i=parseInvite(text,System.currentTimeMillis());return new JSONObject().put("name",Protocol.name(i.optString("name"))).put("expires",i.getLong("expires")); }
    synchronized void join(String text) throws Exception {
        requireReady();JSONObject i=parseInvite(text,System.currentTimeMillis());
        if(db.getString("device").equals(i.getString("from")))throw new IllegalArgumentException("自分の招待です。相手の端末で入力してください");
        if(peer(i.getString("pair"))!=null)throw new IllegalArgumentException("この招待はすでに登録されています");
        for(JSONObject p:peers())if(i.getString("from").equals(p.optString("remote")))throw new IllegalArgumentException("この相手はすでに登録されています");
        KeyPair kp=Protocol.keyPair();String key=Protocol.derive(Protocol.b64(kp.getPrivate().getEncoded()),i.getString("pub"),i.getString("key"),i.getString("pair"));
        JSONObject p=new JSONObject().put("id",i.getString("pair")).put("key",key).put("bootstrap",i.getString("key")).put("pub",Protocol.b64(kp.getPublic().getEncoded()))
            .put("remote",i.getString("from")).put("topic",i.getString("topic")).put("name",Protocol.name(i.optString("name")))
            .put("status","outgoing").put("sharing",true).put("code",Protocol.fingerprint(key)).put("nonce",Protocol.random(16)).put("expires",i.getLong("expires"));
        array("peers").put(p);event("共有申請を送りました");save();retryPair(p.getString("id"));
    }
    synchronized void retryPair(String id) throws Exception {
        JSONObject p=peer(id);if(p==null)return;
        if(System.currentTimeMillis()-p.optLong("retryAt")<15_000)throw new IllegalArgumentException("少し待ってから再送してください");
        p.put("retryAt",System.currentTimeMillis());
        if("outgoing".equals(p.optString("status"))) {
            if(p.optLong("expires")<System.currentTimeMillis())throw new IllegalArgumentException("招待が期限切れです。削除して新しい招待を使ってください");
            JSONObject m=base("join").put("topic",topic()).put("pub",p.getString("pub")).put("name",db.getString("name")).put("nonce",p.getString("nonce"));
            post(p.getString("id"),p.getString("bootstrap"),p.getString("topic"),m,false);
            send(p,base("sync").put("nonce",p.getString("nonce")),false);
        } else if("active".equals(p.optString("status")))send(p,base("approved").put("nonce",p.getString("nonce")),false);
        save();
    }
    synchronized void approve(String id) throws Exception {
        JSONObject p=peer(id);if(p==null||!"incoming".equals(p.optString("status")))return;
        if(p.optLong("expires")<System.currentTimeMillis())throw new IllegalArgumentException("申請が期限切れです。新しい招待を使ってください");
        p.put("status","active").put("sharing",true).put("approvedAt",System.currentTimeMillis());remove("invites",id);
        event(p.getString("name")+"との共有を承認しました");save();send(p,base("approved").put("nonce",p.getString("nonce")),false);
    }
    synchronized void deletePeer(String id) throws Exception {
        JSONObject p=peer(id);if(p==null)return;
        // Remove immediately: queued sensitive sends are rechecked against this state.
        String key=p.getString("key"),t=p.getString("topic");
        remove("peers",id);remove("invites",id);event("共有相手を削除しました");save();
        post(id,key,t,base("revoked"),false);
    }
    synchronized void sharing(String id,boolean value) throws Exception {
        JSONObject p=peer(id);if(p==null||!"active".equals(p.optString("status")))return;
        p.put("sharing",value);save();send(p,base(value?"resumed":"paused"),false);
    }
    synchronized void configure(String name,String mode) throws Exception {
        if(!Arrays.asList("eco","balanced","precise").contains(mode))throw new IllegalArgumentException("測位モードが不正です");
        db.put("name",Protocol.name(name)).put("mode",mode);save();
    }
    synchronized void directMode() throws Exception { db.put("transport","direct");save();connectionNeeded(); }
    synchronized void startPushTest() throws Exception {
        requireReady();pushTest=Protocol.random(24);pushTestUntil=System.currentTimeMillis()+60_000;
        String token=pushTest;
        sender.execute(()->{try {Relay.post(topic(),new JSONObject().put("test",token).toString());}catch(Exception e){error(e.getMessage());}});
        main.postDelayed(()->{synchronized(Engine.this){if(pushTest.equals(token)){pushTest="";error="プッシュ連携を確認できませんでした。ntfy の購読・通知・連携設定を確認してください";changed();}}},60_000);
        changed();
    }
    void external(String topic,String base,String wire) {
        synchronized(this) {
            if(!topic().equals(topic)||!Relay.BASE.equals(base)||wire==null||wire.length()>4096)return;
            try {
                JSONObject m=new JSONObject(wire);
                if(!pushTest.isEmpty()&&System.currentTimeMillis()<pushTestUntil&&pushTest.equals(m.optString("test"))) {
                    pushTest="";db.put("externalVerified",System.currentTimeMillis()).put("transport","external");error="";event("外部プッシュ連携を確認しました");save();connectionNeeded();return;
                }
            }catch(Exception ignored){}
        }
        receiver.execute(()->receive(wire));
    }
    synchronized void requestAll(boolean manual) { for(JSONObject p:peers())if("active".equals(p.optString("status")))request(p.optString("id"),manual); }
    synchronized void request(String id,boolean manual) {
        try {
            JSONObject p=peer(id);if(p==null||!"active".equals(p.optString("status")))return;
            long now=System.currentTimeMillis();
            if(now-p.optLong("requestAt")<(manual?15_000:60_000))return;
            JSONObject m=base("request");String rid=m.getString("mid");
            p.put("requestAt",now).put("rid",rid).put("result","更新をお願いしています").put("resultAt",now);save();
            send(p,m,true /* sensitive flag is handled separately: requests never require own sharing */);
            main.postDelayed(()->{synchronized(Engine.this){try{JSONObject q=peer(id);if(q!=null&&rid.equals(q.optString("rid"))&&q.optLong("resultAt")<=now){q.put("result","応答待ちです。相手がスリープ・停止中の可能性があります");save();}}catch(Exception ignored){}}},30_000);
        }catch(Exception e){error(e.getMessage());}
    }
    synchronized void own() { if(!visible)return;takeFix((loc,err,sampled)->{synchronized(Engine.this){try{if(loc!=null)db.put("own",loc);else error=err;save();}catch(Exception ignored){}}}); }
    private void takeFix(Fixer.Result result) {
        String mode=db.optString("mode","eco");JSONObject cached=db.optJSONObject("own");long now=System.currentTimeMillis();
        if(Protocol.reusable(cached,now,mode)) { try{db.put("reused",db.optInt("reused")+1);}catch(Exception ignored){} result.done(cached,"",false);return; }
        if(!measuring&&lastSampleAt>=0&&SystemClock.elapsedRealtime()-lastSampleAt<60_000) { result.done(null,"再測位まで少し待ってください",false);return; }
        boolean first=!measuring;measuring=true;changed();
        fixer.acquire(mode,(loc,err,sampled)->{
            synchronized(Engine.this) {
                if(first) { measuring=false;if(sampled)lastSampleAt=SystemClock.elapsedRealtime();try{if(sampled)db.put("samples",db.optInt("samples")+1);else if(loc!=null)db.put("reused",db.optInt("reused")+1);if(loc!=null)db.put("own",loc);}catch(Exception ignored){} }
                result.done(loc,err,sampled);save();
            }
        });
    }
    private void requireReady() { if(!fatal.isEmpty())throw new IllegalStateException(fatal); }
    private void send(JSONObject p,JSONObject m,boolean unused) throws Exception { post(p.getString("id"),p.getString("key"),p.getString("topic"),m,"location".equals(m.optString("type"))); }
    private void post(String id,String key,String topic,JSONObject m,boolean sensitive) throws Exception {
        String wire=Protocol.seal(key,id,m);String type=m.getString("type");
        sender.execute(()->{
            try {
                synchronized(Engine.this) {
                    if(sensitive) { JSONObject p=peer(id);if(!enabled()||!serviceActive||p==null||!p.optBoolean("sharing")||!"active".equals(p.optString("status"))||!key.equals(p.optString("key")))return; }
                    if("request".equals(type)&&peer(id)==null)return;
                }
                Relay.post(topic,wire);
                synchronized(Engine.this) { db.put("sent",db.optInt("sent")+1);error="";if(sensitive){JSONObject p=peer(id);if(p!=null){p.put("lastSent",System.currentTimeMillis());event(p.optString("name")+"に位置を送りました");}}save(); }
            }catch(Exception e){synchronized(Engine.this){error=e.getMessage()==null?"送信に失敗しました":e.getMessage();JSONObject p=peer(id);try{if(p!=null&&"request".equals(type))p.put("result","更新要求を送れませんでした").put("resultAt",System.currentTimeMillis());}catch(Exception ignored){}changed();}}
        });
    }
    private synchronized void receive(String wire) {
        try {
            if(wire.length()>4096||!fatal.isEmpty())return;
            JSONObject envelope=new JSONObject(wire);String id=envelope.optString("p");if(!Protocol.id(id))return;
            JSONObject p=peer(id),i=invite(id),m=null;boolean bootstrap=false;
            if(p!=null)try{m=Protocol.open(p.getString("key"),id,wire);}catch(Exception ignored){}
            if(m==null&&i!=null&&i.optLong("expires")>System.currentTimeMillis()) { m=Protocol.open(i.getString("key"),id,wire);bootstrap=true; }
            if(m==null)return;
            long now=System.currentTimeMillis();String type=m.optString("type");
            if(!Protocol.timely(m,now,"request".equals(type)?180_000:900_000))return;
            String mid=m.getString("mid");if(seen.containsKey(mid))return;
            if(bootstrap&&"join".equals(type)) {
                if(!Protocol.id(m.optString("from"))||!Protocol.topic(m.optString("topic"))||!Protocol.id(m.optString("nonce"))||db.getString("device").equals(m.optString("from")))return;
                if(p!=null)return; // one invite, one pending identity; no replacement races
                String key=Protocol.derive(i.getString("priv"),m.getString("pub"),i.getString("key"),id);
                p=new JSONObject().put("id",id).put("key",key).put("remote",m.getString("from")).put("topic",m.getString("topic"))
                    .put("name",Protocol.name(m.optString("name"))).put("nonce",m.getString("nonce")).put("status","incoming").put("sharing",false)
                    .put("code",Protocol.fingerprint(key)).put("expires",i.getLong("expires"));
                array("peers").put(p);event("共有申請が届きました。認証番号を確認してください");
                ShareService.pairNotification(ctx);
            } else {
                if(bootstrap||p==null||!p.getString("remote").equals(m.optString("from")))return;
                if("approved".equals(type)&&"outgoing".equals(p.optString("status"))&&p.getString("nonce").equals(m.optString("nonce"))) {
                    p.put("status","active").put("approvedAt",now);p.remove("bootstrap");p.remove("pub");event(p.getString("name")+"との共有がつながりました");
                    send(p,base("ready"),false);
                } else if("revoked".equals(type)) { p.put("status","revoked").put("sharing",false).put("result","相手が共有を解除しました");p.remove("last"); }
                else if(!"active".equals(p.optString("status")))return;
                else if("sync".equals(type)&&p.getString("nonce").equals(m.optString("nonce")))send(p,base("approved").put("nonce",p.getString("nonce")),false);
                else if("request".equals(type))answer(p,m);
                else if("location".equals(type)) {
                    JSONObject loc=m.optJSONObject("location");
                    if(!p.optString("rid","-").equals(m.optString("rid"))||loc==null||!Protocol.validLocation(loc,now))return;
                    JSONObject previous=p.optJSONObject("last");
                    if(previous==null||loc.getLong("time")>=previous.optLong("time"))p.put("last",loc);
                    p.put("result","位置を受信しました").put("resultAt",now).put("remotePaused",false);
                    p.remove("rid");
                } else if("unavailable".equals(type)) {
                    if(!p.optString("rid","-").equals(m.optString("rid")))return;
                    p.put("result",Protocol.name(m.optString("reason","位置を取得できませんでした"))).put("resultAt",now);p.remove("rid");
                } else if("paused".equals(type)||"resumed".equals(type)) {
                    p.put("remotePaused","paused".equals(type)).put("result","paused".equals(type)?"相手は位置の送信を停止しています":"相手が共有を再開しました").put("resultAt",now);
                }
            }
            seen.put(mid,now);seen.entrySet().removeIf(e->now-e.getValue()>900_000);
            while(seen.size()>512)seen.remove(seen.keySet().iterator().next());
            JSONObject s=new JSONObject();for(Map.Entry<String,Long> e:seen.entrySet())s.put(e.getKey(),e.getValue());
            db.put("seen",s).put("received",db.optInt("received")+1);save();
        } catch(Exception ignored) { /* Reject malformed, expired, replayed or unauthenticated packets without UI noise. */ }
    }
    private void answer(JSONObject p,JSONObject request) throws Exception {
        long now=System.currentTimeMillis();if(now-p.optLong("answerAt")<15_000)return;
        p.put("answerAt",now);String id=p.getString("id"),rid=request.getString("mid");
        if(!enabled()||!serviceActive||!p.optBoolean("sharing")) { send(p,base("unavailable").put("rid",rid).put("reason","相手は位置の送信を停止しています"),false);return; }
        takeFix((location,err,sampled)->{
            synchronized(Engine.this) { try {
                JSONObject current=peer(id);
                if(current==null||!enabled()||!serviceActive||!current.optBoolean("sharing")||!"active".equals(current.optString("status")))return;
                if(location!=null)send(current,base("location").put("rid",rid).put("location",location),true);
                else send(current,base("unavailable").put("rid",rid).put("reason",err),false);
            }catch(Exception e){error(e.getMessage());} }
        });
    }
}
