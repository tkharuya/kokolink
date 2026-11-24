package jp.haruyat.kokolink;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import org.json.JSONObject;
import java.util.*;

/** Bounded one-shot measurements only; never requests continuous location updates. */
final class Fixer {
    interface Result { void done(JSONObject location,String error,boolean sampled); }
    private final Context ctx;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LocationManager manager;
    private final ArrayList<Result> callbacks=new ArrayList<>();
    private final ArrayList<CancellationSignal> signals=new ArrayList<>();
    private long started;
    private long lastAttempt=-1;
    private boolean running;
    private Location best;
    private String mode;
    private PowerManager.WakeLock wake;
    private int generation;
    Fixer(Context ctx) { this.ctx=ctx; manager=(LocationManager)ctx.getSystemService(Context.LOCATION_SERVICE); }
    boolean permitted() { return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    boolean precise() { return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    void acquire(String chosen,Result callback) {
        handler.post(()->begin(chosen,callback));
    }
    private void begin(String chosen,Result callback) {
        if(!permitted()) { callback.done(null,"位置情報の権限がありません",false);return; }
        if(!manager.isLocationEnabled()) { callback.done(null,"端末の位置情報がオフです",false);return; }
        if(running) { callbacks.add(callback);return; }
        Location cached=null;
        for(String p:manager.getAllProviders()) {
            try {
                Location l=manager.getLastKnownLocation(p);
                if(l==null || !l.hasAccuracy())continue;
                long age=(SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos())/1_000_000;
                if(age<0||age>Protocol.cacheMillis(chosen))continue;
                if("precise".equals(chosen)&&l.getAccuracy()>100)continue;
                if(cached==null||l.getElapsedRealtimeNanos()>cached.getElapsedRealtimeNanos())cached=l;
            }catch(SecurityException ignored){}
        }
        if(cached!=null) { callback.done(toJson(cached),"",false);return; }
        long now=SystemClock.elapsedRealtime();
        if(lastAttempt>=0&&now-lastAttempt<60_000) { callback.done(null,"測位の再試行を待っています（最大1分）",false);return; }
        callbacks.add(callback);running=true;best=null;mode=chosen;started=now;lastAttempt=now;
        int gen=++generation;
        wake=((PowerManager)ctx.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"KokoLink:one-shot");
        wake.setReferenceCounted(false);wake.acquire(Protocol.maxFixMillis(mode)+5_000);
        handler.postDelayed(()->{ if(gen==generation&&running)finish(best,best==null?"位置を取得できませんでした。屋外や通信状態を確認してください":""); },Protocol.maxFixMillis(mode));
        if("precise".equals(mode)&&precise()) {
            if(!request("gps",gen))request("network",gen);
        } else {
            boolean asked=request("network",gen);
            if("balanced".equals(mode)&&precise()) {
                handler.postDelayed(()->{ if(gen==generation&&running)request("gps",gen); },asked?5_000:0);
            } else if(!asked) finish(null,"省電力測位を利用できません。設定でバランスを選べます");
        }
    }
    private boolean request(String provider,int gen) {
        try {
            if(!manager.isProviderEnabled(provider)) return false;
            CancellationSignal signal=new CancellationSignal();signals.add(signal);
            java.util.function.Consumer<Location> cb=l->{
                if(!running||gen!=generation)return;
                if(l!=null&&l.hasAccuracy()) {
                    if(best==null||l.getAccuracy()<best.getAccuracy())best=l;
                    if("eco".equals(mode)||l.getAccuracy()<=150||"gps".equals(provider))finish(best,"");
                }
            };
            if(Build.VERSION.SDK_INT>=31) {
                long remaining=Math.max(100,Protocol.maxFixMillis(mode)-(SystemClock.elapsedRealtime()-started));
                LocationRequest r=new LocationRequest.Builder(0).setMaxUpdates(1).setDurationMillis(remaining)
                    .setQuality("gps".equals(provider)?LocationRequest.QUALITY_HIGH_ACCURACY:LocationRequest.QUALITY_LOW_POWER).build();
                manager.getCurrentLocation(provider,r,signal,ctx.getMainExecutor(),cb);
            } else manager.getCurrentLocation(provider,signal,ctx.getMainExecutor(),cb);
            return true;
        }catch(Exception ignored) { return false; }
    }
    private void finish(Location l,String err) {
        if(!running)return;
        running=false;generation++;
        for(CancellationSignal s:signals)s.cancel();signals.clear();
        if(wake!=null&&wake.isHeld())wake.release();
        JSONObject result=l==null?null:toJson(l);
        ArrayList<Result> current=new ArrayList<>(callbacks);callbacks.clear();
        for(Result cb:current)cb.done(result,err,true);
    }
    void cancel() { handler.post(()->finish(null,"共有を停止しました")); }
    private JSONObject toJson(Location l) {
        try {
            Intent battery=ctx.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int pct=-1;
            if(battery!=null&&battery.getIntExtra(BatteryManager.EXTRA_SCALE,0)>0)
                pct=100*battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)/battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
            return new JSONObject().put("lat",l.getLatitude()).put("lon",l.getLongitude()).put("acc",l.getAccuracy())
                .put("time",l.getTime()).put("battery",pct).put("approx",!precise()).put("provider",l.getProvider());
        } catch(Exception e) { return null; }
    }
}
