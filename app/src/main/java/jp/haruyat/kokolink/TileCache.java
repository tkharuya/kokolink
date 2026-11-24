package jp.haruyat.kokolink;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.*;
import java.util.*;

/** Only visible map tiles; seven-day disk cache and conditional revalidation. */
final class TileCache {
    final File dir;
    private long lastTrim;
    TileCache(Context c) { dir=new File(c.getCacheDir(),"tiles");dir.mkdirs(); }
    WebResourceResponse get(Uri uri) {
        try {
            String path=uri.getPath();
            if(!path.matches("/[0-9]{1,2}/[0-9]{1,7}/[0-9]{1,7}\\.png"))return empty();
            File tile=new File(dir,path.substring(1).replace('/','_'));
            if(tile.exists()&&System.currentTimeMillis()-tile.lastModified()<7*86400_000L)return png(tile);
            HttpURLConnection c=Relay.connection(new URL("https://tile.openstreetmap.org"+path));
            c.setConnectTimeout(8000);c.setReadTimeout(8000);
            if(tile.exists())c.setIfModifiedSince(tile.lastModified());
            try {
                int status=c.getResponseCode();
                if(status==304&&tile.exists()) { tile.setLastModified(System.currentTimeMillis());return png(tile); }
                if(status==200) {
                    byte[] b;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                        byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>1024*1024)throw new IOException("tile");}b=out.toByteArray();
                    }
                    File temp=File.createTempFile("tile",".tmp",dir);
                    try(FileOutputStream out=new FileOutputStream(temp)){out.write(b);}
                    if(!temp.renameTo(tile))temp.delete();trim();
                    return new WebResourceResponse("image/png",null,new ByteArrayInputStream(b));
                }
            }finally{c.disconnect();}
            return tile.exists()?png(tile):empty();
        }catch(Exception e){return empty();}
    }
    private synchronized void trim() {
        if(System.currentTimeMillis()-lastTrim<3600_000)return;lastTrim=System.currentTimeMillis();
        File[] files=dir.listFiles();if(files==null)return;
        long size=0;for(File f:files)size+=f.length();
        if(size<64*1024*1024)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File f:files){if(size<48*1024*1024)break;long n=f.length();if(f.delete())size-=n;}
    }
    private WebResourceResponse png(File f) throws Exception { return new WebResourceResponse("image/png",null,new FileInputStream(f)); }
    static WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
}
