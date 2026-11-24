package jp.haruyat.kokolink;
import java.util.*;
import org.json.JSONObject;
final class Fixer {
 interface Result {void done(JSONObject location,String error,boolean sampled);}
 final List<Result> waiting=new ArrayList<>();int acquisitions;
 Fixer(android.content.Context c){}
 boolean permitted(){return true;}
 boolean precise(){return true;}
 synchronized void acquire(String mode,Result result){acquisitions++;waiting.add(result);}
 synchronized void complete(JSONObject l){List<Result> c=new ArrayList<>(waiting);waiting.clear();for(Result r:c)r.done(l,"",true);}
 void cancel(){complete(null);}
}
