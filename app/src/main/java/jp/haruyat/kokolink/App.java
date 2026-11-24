package jp.haruyat.kokolink;
import android.app.Application;
import android.content.*;
import android.os.Build;
import android.net.*;
public final class App extends Application {
    public Engine engine;
    @Override public void onCreate() {
        super.onCreate(); engine=new Engine(this);
        BroadcastReceiver r=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i) {
                if(!"io.heckel.ntfy.MESSAGE_RECEIVED".equals(i.getAction()))return;
                engine.external(i.getStringExtra("topic"),i.getStringExtra("base_url"),i.getStringExtra("message"));
            }
        };
        IntentFilter f=new IntentFilter("io.heckel.ntfy.MESSAGE_RECEIVED");
        if(Build.VERSION.SDK_INT>=33)registerReceiver(r,f,Context.RECEIVER_EXPORTED);else registerReceiver(r,f);
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){engine.networkChanged();}
        });
    }
}
