package jp.haruyat.kokolink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** HTTPS transport. The relay sees ciphertext and routing metadata, never coordinates. */
final class Relay {
    static final String BASE="https://ntfy.sh";
    interface Events { void connected(boolean ok,String error); void message(String wire); }
    private volatile boolean stopped=true;
    private volatile HttpURLConnection connection;
    private String cursor="15m";
    private Thread thread;
    static HttpURLConnection connection(URL url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15_000);c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent","KokoLink/1.0 (Android; jp.haruyat.kokolink)");
        return c;
    }
    static void post(String topic,String wire) throws Exception {
        if(!Protocol.topic(topic)||wire.length()>3800) throw new SecurityException("送信先が不正です");
        HttpURLConnection c=connection(new URL(BASE+"/"+topic));
        try {
            c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","text/plain; charset=utf-8");
            c.setRequestProperty("Priority","2");
            c.setRequestProperty("Title","KokoLink");
            byte[] b=wire.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);
            try(OutputStream out=c.getOutputStream()) { out.write(b); }
            int status=c.getResponseCode();
            if(status==429) throw new IOException("中継サービスの送信上限です。時間をおいてください");
            if(status!=200) throw new IOException("中継サービスに接続できません ("+status+")");
            try(InputStream in=c.getInputStream()) { byte[] buf=new byte[2048];while(in.read(buf)!=-1){} }
        } finally { c.disconnect(); }
    }
    synchronized void start(String topic,Events events) {
        if(!stopped) return;
        stopped=false;
        thread=new Thread(()->{
            long wait=2_000;
            while(!stopped) {
                HttpURLConnection c=null;long connectedAt=0;
                try {
                    c=connection(new URL(BASE+"/"+topic+"/json?since="+cursor)); connection=c;
                    c.setReadTimeout(90_000);
                    int status=c.getResponseCode();
                    if(status!=200) { if(status==429) wait=300_000; throw new IOException("受信に接続できません ("+status+")"); }
                    connectedAt=System.currentTimeMillis();events.connected(true,"");
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;
                        while(!stopped && (line=readBoundedLine(reader,16_384))!=null) {
                            if(line.isEmpty()) continue;
                            JSONObject e=new JSONObject(line);
                            if("message".equals(e.optString("event"))) {
                                String receivedId=e.optString("id");
                                if(receivedId.matches("[A-Za-z0-9_-]{8,64}"))cursor=receivedId;
                                String wire=e.optString("message");
                                if(wire.length()<=4096) events.message(wire);
                            }
                        }
                    }
                    if(!stopped) events.connected(false,"再接続を待っています");
                } catch(Exception e) { if(!stopped) events.connected(false,"通信待ち・スリープ中は更新が遅れる場合があります"); }
                finally { if(c!=null)c.disconnect(); if(connection==c)connection=null; }
                if(stopped) break;
                if(connectedAt>0&&System.currentTimeMillis()-connectedAt>60_000)wait=2_000;
                try { Thread.sleep(wait+(long)(Math.random()*1000)); } catch(InterruptedException ignored) { break; }
                wait=Math.min(300_000,wait*2);
            }
        },"KokoLink-receive");
        thread.setDaemon(true);thread.start();
    }
    static String readBoundedLine(Reader reader,int max) throws IOException {
        StringBuilder b=new StringBuilder();int n;
        while((n=reader.read())!=-1) { if(n=='\n') return b.toString();if(b.length()>=max)throw new IOException("message size");if(n!='\r')b.append((char)n); }
        return b.length()==0?null:b.toString();
    }
    synchronized void stop() {
        stopped=true;
        if(connection!=null) { HttpURLConnection c=connection; connection=null; new Thread(c::disconnect).start(); }
        if(thread!=null)thread.interrupt();thread=null;
    }
}
