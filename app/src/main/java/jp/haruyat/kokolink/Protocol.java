package jp.haruyat.kokolink;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Wire format only: no Android dependencies, exercised on the JVM. */
public final class Protocol {
    private static final SecureRandom RNG = new SecureRandom();
    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] un64(String s) { return Base64.getUrlDecoder().decode(s); }
    public static String random(int bytes) { byte[] b = new byte[bytes]; RNG.nextBytes(b); return b64(b); }
    public static boolean id(String s) { return s != null && s.matches("[A-Za-z0-9_-]{16,64}"); }
    public static boolean topic(String s) { return s != null && s.matches("kl_[A-Za-z0-9_-]{32}"); }
    public static String name(String s) {
        if (s == null) return "相手";
        s = s.replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]", "").trim();
        return s.isEmpty() ? "相手" : s.substring(0, Math.min(32, s.length()));
    }
    public static KeyPair keyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1")); return g.generateKeyPair();
    }
    public static String derive(String privateKey, String publicKey, String salt, String pair) throws Exception {
        KeyFactory f = KeyFactory.getInstance("EC");
        PrivateKey p = f.generatePrivate(new PKCS8EncodedKeySpec(un64(privateKey)));
        PublicKey q = f.generatePublic(new X509EncodedKeySpec(un64(publicKey)));
        if (!(q instanceof java.security.interfaces.ECPublicKey) || ((java.security.interfaces.ECPublicKey)q).getParams().getCurve().getField().getFieldSize() != 256) throw new SecurityException("curve");
        KeyAgreement a = KeyAgreement.getInstance("ECDH"); a.init(p); a.doPhase(q, true);
        Mac h = Mac.getInstance("HmacSHA256"); h.init(new SecretKeySpec(un64(salt), "HmacSHA256"));
        byte[] prk = h.doFinal(a.generateSecret()); h.init(new SecretKeySpec(prk,"HmacSHA256"));
        return b64(h.doFinal(("KokoLink/session/v1/" + pair + "\u0001").getBytes(StandardCharsets.UTF_8)));
    }
    public static String fingerprint(String key) throws Exception {
        byte[] b = MessageDigest.getInstance("SHA-256").digest(un64(key));
        long n = ((b[0]&255L)<<24)|((b[1]&255L)<<16)|((b[2]&255L)<<8)|(b[3]&255L);
        return String.format(Locale.ROOT,"%04d %04d", n % 10000, (n / 10000) % 10000);
    }
    public static String seal(String key, String pair, JSONObject payload) throws Exception {
        if (!id(pair) || un64(key).length != 32) throw new SecurityException("key");
        byte[] iv = new byte[12]; RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(un64(key),"AES"), new GCMParameterSpec(128,iv));
        c.updateAAD(("KokoLink/1/"+pair).getBytes(StandardCharsets.UTF_8));
        byte[] enc = c.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        String wire = new JSONObject().put("v",1).put("p",pair).put("n",b64(iv)).put("c",b64(enc)).toString();
        if (wire.length()>3800) throw new IllegalArgumentException("message too long");
        return wire;
    }
    public static JSONObject open(String key, String pair, String wire) throws Exception {
        if (wire.length()>4096) throw new SecurityException("length");
        JSONObject e = new JSONObject(wire);
        if(e.getInt("v")!=1 || !pair.equals(e.getString("p"))) throw new SecurityException("envelope");
        byte[] iv = un64(e.getString("n")); if(iv.length!=12) throw new SecurityException("nonce");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(un64(key),"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(("KokoLink/1/"+pair).getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(c.doFinal(un64(e.getString("c"))),StandardCharsets.UTF_8));
    }
    public static JSONObject message(String type,String from,long now) throws Exception {
        return new JSONObject().put("type",type).put("from",from).put("time",now).put("mid",random(16));
    }
    public static boolean timely(JSONObject m,long now,long ttl) {
        long t=m.optLong("time",0); return t>0 && t<=now+60_000 && now-t<=ttl && id(m.optString("mid"));
    }
    public static boolean validLocation(JSONObject l,long now) {
        double lat=l.optDouble("lat",Double.NaN),lon=l.optDouble("lon",Double.NaN),acc=l.optDouble("acc",Double.NaN);
        return Double.isFinite(lat)&&Double.isFinite(lon)&&Double.isFinite(acc)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180&&acc>=0&&acc<=100_000&&l.optLong("time")>0&&l.optLong("time")<=now+60_000;
    }
    public static long cacheMillis(String mode) { return "precise".equals(mode)?60_000:"balanced".equals(mode)?120_000:300_000; }
    public static long maxFixMillis(String mode) { return "precise".equals(mode)?20_000:15_000; }
    public static boolean reusable(JSONObject l,long now,String mode) { return l!=null&&validLocation(l,now)&&now-l.optLong("time")<=cacheMillis(mode)&&(!"precise".equals(mode)||l.optDouble("acc",Double.MAX_VALUE)<=100); }
    private Protocol() {}
}
