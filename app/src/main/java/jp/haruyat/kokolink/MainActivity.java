package jp.haruyat.kokolink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private WebView web;
    private Engine engine;
    private boolean resumed,loaded;
    private String incoming="";
    private String afterPermission="";
    private static final String ORIGIN="https://app.kokolink.local/";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);engine=((App)getApplication()).engine;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setBackgroundColor(Color.rgb(246,247,242));root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,-1));setContentView(root);
        web.setBackgroundColor(Color.rgb(246,247,242));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);s.setUserAgentString(s.getUserAgentString()+" KokoLink/1.0");
        WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new Bridge(),"Native");
        TileCache tiles=new TileCache(this);
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req) {
                Uri u=req.getUrl();
                if("https".equals(u.getScheme())&&"app.kokolink.local".equals(u.getHost())) {
                    try {
                        String path=u.getPath();if(path==null||path.equals("/"))path="/index.html";
                        if(path.contains("..")||!path.matches("/[a-zA-Z0-9_./-]+"))return TileCache.empty();
                        String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".html")?"text/html":"text/plain";
                        return new WebResourceResponse(mime,"UTF-8",getAssets().open(path.substring(1)));
                    }catch(Exception e){return TileCache.empty();}
                }
                if("https".equals(u.getScheme())&&"tile.openstreetmap.org".equals(u.getHost()))return tiles.get(u);
                return TileCache.empty();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r) {
                Uri u=r.getUrl();
                if(u.toString().startsWith(ORIGIN))return false;
                if(r.hasGesture()&&"https".equals(u.getScheme())&&Arrays.asList("www.openstreetmap.org","docs.ntfy.sh","ntfy.sh").contains(u.getHost()))openExternal(u);
                return true;
            }
            @Override public void onPageFinished(WebView v,String url){if(url.startsWith(ORIGIN)){loaded=true;render();showIncoming();}}
        });
        web.setWebChromeClient(new WebChromeClient());
        engine.setListener(this::render);captureIntent(getIntent());web.loadUrl(ORIGIN+"index.html");
    }
    @Override protected void onResume() {
        super.onResume();resumed=true;web.onResume();web.resumeTimers();engine.visible(true);
        if(engine.enabled()&&engine.fixer.permitted()&&notificationsAllowed())startSharing();
        render();showIncoming();if(loaded)web.evaluateJavascript("window.appOpened&&window.appOpened()",null);
    }
    @Override protected void onPause() { resumed=false;engine.visible(false);web.onPause();web.pauseTimers();super.onPause(); }
    @Override protected void onDestroy(){engine.setListener(null);web.removeJavascriptInterface("Native");web.destroy();super.onDestroy();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);captureIntent(i);showIncoming();}
    private void captureIntent(Intent i){if(i==null)return;String text=i.getStringExtra(Intent.EXTRA_TEXT);if(text!=null)incoming=text;if(i.getData()!=null&&"kokolink".equals(i.getData().getScheme()))incoming=i.getData().toString();}
    private void showIncoming(){if(resumed&&loaded&&!incoming.isEmpty()){String text=incoming;incoming="";web.evaluateJavascript("window.receiveInvite("+JSONObject.quote(text)+")",null);}}
    private void render(){if(!resumed||!loaded||isFinishing())return;String json=engine.snapshot().toString();runOnUiThread(()->{if(resumed&&!isFinishing())web.evaluateJavascript("window.renderNative("+json+")",null);});}
    private boolean notificationsAllowed(){return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    private void permission(String next){
        afterPermission=next;ArrayList<String> p=new ArrayList<>();
        if(!engine.fixer.permitted()){p.add(Manifest.permission.ACCESS_COARSE_LOCATION);p.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if("enable".equals(next)&&!notificationsAllowed())p.add(Manifest.permission.POST_NOTIFICATIONS);
        if(p.isEmpty()){afterPermission="";finishPermission(next);return;}
        requestPermissions(p.toArray(new String[0]),42);
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] results){super.onRequestPermissionsResult(request,perms,results);if(request==42){String next=afterPermission;afterPermission="";finishPermission(next);}}
    private void finishPermission(String next){
        if(!engine.fixer.permitted()){engine.error("位置情報を許可してください。設定から変更できます");return;}
        if("enable".equals(next)){
            if(!notificationsAllowed()){engine.error("共有状態と停止ボタンを表示するため、通知を許可してください");return;}
            try{engine.enable(true);startSharing();}catch(Exception e){engine.error(e.getMessage());}
        }else if("own".equals(next))engine.own();
        render();
    }
    private void startSharing(){if(!resumed)return;try{startForegroundService(new Intent(this,ShareService.class));}catch(Exception e){engine.error("共有を開始できません。権限と電池設定を確認してください");}}
    private void openExternal(Uri uri){try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){Toast.makeText(this,"対応するアプリがありません",Toast.LENGTH_SHORT).show();}}
    @Override public void onBackPressed(){if(loaded)web.evaluateJavascript("window.goBack&&window.goBack()",v->{if("false".equals(v))MainActivity.super.onBackPressed();});else super.onBackPressed();}

    final class Bridge {
        @JavascriptInterface public String call(String input){
            try {
                if(input==null||input.length()>16_000)throw new IllegalArgumentException("入力が長すぎます");
                JSONObject m=new JSONObject(input);String action=m.getString("action");Object data=JSONObject.NULL;
                switch(action){
                    case "state":data=engine.snapshot();break;
                    case "enable":runOnUiThread(()->permission("enable"));break;
                    case "disable":engine.enable(false);runOnUiThread(()->stopService(new Intent(MainActivity.this,ShareService.class)));break;
                    case "own":runOnUiThread(()->permission("own"));break;
                    case "refresh":if(m.has("id"))engine.request(m.getString("id"),m.optBoolean("manual"));else engine.requestAll(m.optBoolean("manual"));break;
                    case "invite":data=engine.createInvite();break;
                    case "inspect":data=engine.inspectInvite(m.getString("text"));break;
                    case "join":engine.join(m.getString("text"));break;
                    case "approve":engine.approve(m.getString("id"));break;
                    case "delete":engine.deletePeer(m.getString("id"));break;
                    case "sharing":engine.sharing(m.getString("id"),m.getBoolean("value"));break;
                    case "retry":engine.retryPair(m.getString("id"));break;
                    case "settings":engine.configure(m.getString("name"),m.getString("mode"));break;
                    case "direct":engine.directMode();break;
                    case "pushTest":engine.startPushTest();break;
                    case "copy":{String text=m.getString("text");runOnUiThread(()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ここリンク",text));Toast.makeText(MainActivity.this,"コピーしました",Toast.LENGTH_SHORT).show();});break;}
                    case "share":{String text=m.getString("text");runOnUiThread(()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"招待を送る")));break;}
                    case "appSettings":runOnUiThread(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));break;
                    case "batterySettings":runOnUiThread(()->startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));break;
                    case "locationSettings":runOnUiThread(()->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));break;
                    case "ntfy":runOnUiThread(()->{try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("ntfy://ntfy.sh/"+engine.topic()+"?display=KokoLink")).setPackage("io.heckel.ntfy");startActivity(i);}catch(Exception e){openExternal(Uri.parse("https://play.google.com/store/apps/details?id=io.heckel.ntfy"));}});break;
                    case "map":{double lat=m.getDouble("lat"),lon=m.getDouble("lon");if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IllegalArgumentException("位置が不正です");runOnUiThread(()->openExternal(Uri.parse("geo:"+lat+","+lon+"?q="+lat+","+lon)));break;}
                    default:throw new IllegalArgumentException("不明な操作です");
                }
                return new JSONObject().put("ok",true).put("data",data).toString();
            }catch(Exception e){try{return new JSONObject().put("ok",false).put("error",e.getMessage()==null?"操作に失敗しました":e.getMessage()).toString();}catch(Exception ignored){return "{\"ok\":false}";}}
        }
    }
}
