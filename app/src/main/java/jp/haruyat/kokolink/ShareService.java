package jp.haruyat.kokolink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class ShareService extends Service {
    static final String CHANNEL="sharing";
    static final String STOP="jp.haruyat.kokolink.STOP";
    private Engine engine;
    @Override public void onCreate() { super.onCreate();engine=((App)getApplication()).engine;channels(this); }
    static void channels(Context c) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        NotificationChannel ch=new NotificationChannel(CHANNEL,"位置共有の待ち受け",NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPSは要求されたときだけ使用します");ch.setShowBadge(false);nm.createNotificationChannel(ch);
        nm.createNotificationChannel(new NotificationChannel("pairing","共有相手の確認",NotificationManager.IMPORTANCE_DEFAULT));
    }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i!=null&&STOP.equals(i.getAction())) {
            try{engine.enable(false);}catch(Exception ignored){}
            engine.service(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;
        }
        if(!engine.enabled()||!engine.fixer.permitted()) { stopSelf();return START_NOT_STICKY; }
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ShareService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("ここリンク · 共有中")
            .setContentText("要求が届いたときだけ測位します").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"共有を停止",stop).build()).build();
        try { startForeground(101,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);engine.service(true); }
        catch(Exception e) { engine.error("待ち受けを開始できません。アプリを開いて権限を確認してください");engine.service(false);stopSelf(); }
        return START_NOT_STICKY;
    }
    static void pairNotification(Context c) {
        channels(c);
        PendingIntent open=PendingIntent.getActivity(c,2,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(c,"pairing").setSmallIcon(R.drawable.ic_notify).setContentTitle("ここリンク · 共有申請")
            .setContentText("アプリで相手と認証番号を確認してください").setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        try{c.getSystemService(NotificationManager.class).notify(102,n);}catch(SecurityException ignored){}
    }
    @Override public void onDestroy() { engine.service(false);engine.fixer.cancel();super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
