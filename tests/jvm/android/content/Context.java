package android.content;
public class Context {
 public static final String POWER_SERVICE="power";
 public Object getSystemService(String name){return new android.os.PowerManager();}
 public String getPackageName(){return "test";}
}
