package jp.haruyat.kokolink;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Entire state, including pairing secrets and locations, encrypted at rest. */
final class Vault {
    private final AtomicFile file;
    private final javax.crypto.SecretKey key;
    Vault(Context ctx) throws Exception {
        file=new AtomicFile(new File(ctx.getNoBackupFilesDir(),"state.enc"));
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String alias="kokolink-state-v1";
        if(!ks.containsAlias(alias)) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            gen.generateKey();
        }
        key=(javax.crypto.SecretKey)ks.getKey(alias,null);
    }
    JSONObject read() throws Exception {
        if(!file.getBaseFile().exists()) return null;
        byte[] b=file.readFully(); if(b.length<29) throw new IOException("保存データを読み込めません");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,b,0,12));
        return new JSONObject(new String(c.doFinal(b,12,b.length-12),StandardCharsets.UTF_8));
    }
    void write(JSONObject state) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);
        byte[] data=c.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream out=file.startWrite();
        try { out.write(c.getIV());out.write(data);file.finishWrite(out); }
        catch(Exception e) { file.failWrite(out);throw e; }
    }
}
