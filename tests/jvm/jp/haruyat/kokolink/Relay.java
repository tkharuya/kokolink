package jp.haruyat.kokolink;
import java.util.*;
import java.util.concurrent.*;
final class Relay {
 static final String BASE="https://ntfy.sh";
 interface Events {void connected(boolean ok,String error);void message(String wire);}
 static final Map<String,Events> bus=new ConcurrentHashMap<>();
 static final List<String[]> traffic=Collections.synchronizedList(new ArrayList<>());
 String topic;
 void start(String t,Events e){topic=t;bus.put(t,e);e.connected(true,"");}
 void stop(){bus.remove(topic);}
 static void post(String t,String wire){traffic.add(new String[]{t,wire});Events e=bus.get(t);if(e!=null)e.message(wire);}
}
