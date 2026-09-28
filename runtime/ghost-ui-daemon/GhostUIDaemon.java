import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.*;
import android.util.SparseArray;
import android.view.accessibility.*;
import android.graphics.Rect;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class GhostUIDaemon {
 static UiAutomation ui;
 static class N {
  AccessibilityNodeInfo ref; String text, desc, cls, pkg; Rect b;
  boolean visible, enabled, editable;
  N(AccessibilityNodeInfo r){ref=r;text=s(r.getText());desc=s(r.getContentDescription());
   cls=s(r.getClassName());pkg=s(r.getPackageName());b=new Rect();r.getBoundsInScreen(b);
   visible=r.isVisibleToUser();enabled=r.isEnabled();editable=r.isEditable();}
 }
 static String s(CharSequence x){return x==null?"":x.toString();}
 static void walk(AccessibilityNodeInfo n,List<N> out,int depth){
  if(n==null||depth>70||out.size()>5000)return;
  out.add(new N(n));
  for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),out,depth+1);
 }
 static List<N> nodes(int display){
  ArrayList<N> out=new ArrayList<>();
  SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
  List<AccessibilityWindowInfo> ws=all.get(display);
  if(ws!=null)for(AccessibilityWindowInfo w:ws)walk(w.getRoot(),out,0);
  return out;
 }
 static boolean clickNode(AccessibilityNodeInfo n){
  AccessibilityNodeInfo original=n;
  while(n!=null&&!n.isClickable())n=n.getParent();
  if(n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
  if(original!=null){
   Rect r=new Rect(); original.getBoundsInScreen(r);
   if(!r.isEmpty()){
    try{
     String cmd="input tap "+r.centerX()+" "+r.centerY();
     return Runtime.getRuntime().exec(new String[]{"sh","-c",cmd}).waitFor()==0;
    }catch(Exception ignored){}
   }
  }
  return false;
 }
 static boolean clickDesc(int display,String pkg,String desc){
  for(N n:nodes(display))if(n.visible&&n.enabled&&n.pkg.equals(pkg)&&n.desc.equalsIgnoreCase(desc))
   return clickNode(n.ref);
  return false;
 }
 static boolean clickSend(int display,String pkg,String wanted){
  for(N n:nodes(display))if(n.visible&&n.enabled&&n.pkg.equals(pkg)){
   String d=n.desc.toLowerCase(Locale.ROOT);
   if(n.desc.equalsIgnoreCase(wanted)||(d.startsWith("send")&&!d.contains("feedback")))
    if(clickNode(n.ref))return true;
  }
  return false;
 }
 static boolean setText(int display,String pkg,String text){
  N best=null;
  for(N n:nodes(display))if(n.visible&&n.enabled&&n.pkg.equals(pkg)&&
      (n.editable||n.cls.equals("android.widget.EditText")))
   if(best==null||n.b.bottom>best.b.bottom)best=n;
  if(best==null)return false;
  Bundle b=new Bundle(); b.putCharSequence(
   AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
  return best.ref.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);
 }
 static Set<String> visibleTexts(int display,String pkg){
  LinkedHashSet<String> out=new LinkedHashSet<>();
  for(N n:nodes(display))if(n.visible&&n.pkg.equals(pkg)&&n.cls.equals("android.widget.TextView")){
   String t=n.text.trim(); if(!t.isEmpty())out.add(t);
  }
  return out;
 }
 static boolean generating(int display,String pkg){
  for(N n:nodes(display))if(n.visible&&n.pkg.equals(pkg)){
   String x=(n.text+" "+n.desc).toLowerCase(Locale.ROOT);
   if(x.contains("stop responding")||x.contains("stop response")||
      x.contains("claude is writing")||x.contains("cancel response"))return true;
  }
  return false;
 }
 static boolean junk(String t){
  String x=t.trim();
  if(x.isEmpty())return true;
  String l=x.toLowerCase(Locale.ROOT);
  return x.equals("Working")||x.equals("Show more")||x.equals("Thinking")||
   x.equals("Chat")||x.equals("Work")||x.equals("Medium")||
   l.equals("gemini is ai and can make mistakes.")||
   l.equals("claude is ai and can make mistakes.")||
   l.equals("reply to claude…")||l.equals("reply to chatgpt")||
   l.equals("reply to gemini")||
   l.equals("chatgpt can make mistakes. check important info.");
 }
 static String candidate(int display,String pkg,String mode,String prompt,Set<String> before){
  ArrayList<String> found=new ArrayList<>();
  int maxX=mode.equals("claude")?72:mode.equals("gemini")?88:110;
  for(N n:nodes(display)){
   if(!n.visible||!n.pkg.equals(pkg)||!n.cls.equals("android.widget.TextView"))continue;
   String t=n.text.trim();
   if(t.equals(prompt)||before.contains(t)||junk(t)||n.b.left>maxX)continue;
   if(!t.isEmpty()&&(found.isEmpty()||!found.get(found.size()-1).equals(t)))found.add(t);
  }
  return String.join("\n\n",found).trim();
 }
 static JSONObject chat(JSONObject q)throws Exception{
  int display=q.getInt("display"); if(display==0)throw new Exception("physical display prohibited");
  String pkg=q.getString("package"), mode=q.getString("mode");
  String prompt=q.getString("prompt"), send=q.optString("send_desc","Send");
  String fresh=q.optString("new_chat_desc","New chat");
  int timeout=q.optInt("timeout_ms",120000);
  boolean freshClicked=false;
  if(!fresh.isEmpty()&&!fresh.equals("NOOP"))freshClicked=clickDesc(display,pkg,fresh);
  if(freshClicked)Thread.sleep(450);
  long ready=SystemClock.elapsedRealtime()+2200;
  boolean typed=false;
  while(SystemClock.elapsedRealtime()<ready){
   if(setText(display,pkg,prompt)){typed=true;break;}
   Thread.sleep(45);
  }
  if(!typed)throw new Exception("composer not found");
  Set<String> before=visibleTexts(display,pkg);
  long sendUntil=SystemClock.elapsedRealtime()+2500; boolean sent=false;
  while(SystemClock.elapsedRealtime()<sendUntil){
   if(clickSend(display,pkg,send)){sent=true;break;}
   Thread.sleep(35);
  }
  if(!sent)throw new Exception("send control not found");
  long start=SystemClock.elapsedRealtime(), stableAt=start; boolean sawGen=false;
  String last="",answer="";
  while(SystemClock.elapsedRealtime()-start<timeout){
   Thread.sleep(90);
   boolean gen=generating(display,pkg); if(gen)sawGen=true;
   String c=candidate(display,pkg,mode,prompt,before);
   long now=SystemClock.elapsedRealtime();
   if(!c.equals(last)){last=c;stableAt=now;}
   if(!c.isEmpty()&&!gen){
    if((sawGen&&now-stableAt>=120)||(!sawGen&&now-stableAt>=650)){answer=c;break;}
   }
  }
  if(answer.isEmpty())throw new Exception("no stable final reply");
  return new JSONObject().put("ok",true).put("text",answer)
   .put("elapsed_ms",SystemClock.elapsedRealtime()-start);
 }
 static JSONObject snapshot(int display,String pkg)throws Exception{
  JSONArray a=new JSONArray();
  for(N n:nodes(display))if(n.visible&&(pkg.isEmpty()||n.pkg.equals(pkg))&&
      (!n.text.isEmpty()||!n.desc.isEmpty()||n.editable))
   a.put(new JSONObject().put("text",n.text).put("desc",n.desc).put("class",n.cls)
    .put("pkg",n.pkg).put("editable",n.editable)
    .put("bounds",new JSONArray(new int[]{n.b.left,n.b.top,n.b.right,n.b.bottom})));
  return new JSONObject().put("ok",true).put("nodes",a);
 }
 static JSONObject handle(String line){
  try{
   JSONObject q=new JSONObject(line);
   String op=q.optString("op","");
   if(op.equals("health"))return new JSONObject().put("ok",true);
   if(op.equals("snapshot"))return snapshot(q.getInt("display"),q.optString("package",""));
   if(op.equals("click_desc"))return new JSONObject().put("ok",
    clickDesc(q.getInt("display"),q.getString("package"),q.getString("description")));
   if(op.equals("chat"))return chat(q);
   return new JSONObject().put("ok",false).put("error","unknown op");
  }catch(Throwable e){
   JSONObject out=new JSONObject();
   try{out.put("ok",false);out.put("error",e.toString());}catch(Exception ignored){}
   return out;
  }
 }
 static void client(Socket s){
  try{
   BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));
   BufferedWriter w=new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
   String line=r.readLine(); w.write(handle(line).toString()); w.write("\n"); w.flush();
  }catch(Exception ignored){}finally{try{s.close();}catch(Exception ignored){}}
 }
 static void serve(int port)throws Exception{
  ServerSocket ss=new ServerSocket();
  ss.setReuseAddress(true);
  ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));
  while(true){ Socket s=ss.accept(); new Thread(()->client(s),"ghost-ui-client").start(); }
 }
 public static void main(String[] args)throws Exception{
  if(args.length<1)throw new IllegalArgumentException("GhostUIDaemon PORT");
  int port=Integer.parseInt(args[0]);
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
  HandlerThread ht=new HandlerThread("ghost-ui-daemon"); ht.start();
  Class<?> ic=Class.forName("android.app.IUiAutomationConnection");
  Object conn=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
  ui=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,ic).newInstance(ht.getLooper(),conn);
  UiAutomation.class.getMethod("connect",int.class).invoke(ui,1);
  AccessibilityServiceInfo si=ui.getServiceInfo();
  si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
  ui.setServiceInfo(si);
  serve(port);
 }
}
