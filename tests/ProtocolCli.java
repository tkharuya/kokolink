package jp.haruyat.kokolink;
import org.json.JSONObject;
public class ProtocolCli {
 public static void main(String[] args)throws Exception {
  if(args.length==0){
   String key=Protocol.random(32),id=Protocol.random(16);
   JSONObject msg=Protocol.message("integration-test",Protocol.random(16),System.currentTimeMillis()).put("payload","synthetic-test-only");
   System.out.println(new JSONObject().put("key",key).put("id",id).put("wire",Protocol.seal(key,id,msg)));
  }else{
   JSONObject f=new JSONObject(new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   JSONObject m=Protocol.open(f.getString("key"),f.getString("id"),f.getString("wire"));
   if(!"synthetic-test-only".equals(m.getString("payload")))throw new AssertionError();
   System.out.println("PASS live relay preserved authenticated ciphertext");
  }
 }
}
