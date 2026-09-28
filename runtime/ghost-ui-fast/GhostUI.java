import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.*;
import android.util.SparseArray;
import android.view.accessibility.*;
import android.graphics.Rect;
import org.json.*;
import java.util.*;

public class GhostUI {
 static String s(CharSequence x){return x==null?"":x.toString();}
 static UiAutomation ui; static int display;
 static List<AccessibilityWindowInfo> windows() {
  SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
  List<AccessibilityWindowInfo> w=all.get(display); return w==null?Collections.emptyList():w;
 }
 static void walk(AccessibilityNodeInfo n, ArrayList<AccessibilityNodeInfo> out, int depth){
  if(n==null||depth>60||out.size()>5000)return;
  out.add(n);
  for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),out,depth+1);
 }
 static ArrayList<AccessibilityNodeInfo> allNodes(){
  ArrayList<AccessibilityNodeInfo> out=new ArrayList<>();
  for(AccessibilityWindowInfo w:windows())walk(w.getRoot(),out,0);
  return out;
 }
 static AccessibilityNodeInfo find(String field,String value,String pkg){
  AccessibilityNodeInfo hit=null; int count=0;
  for(AccessibilityNodeInfo n:allNodes()){
   if(!n.isVisibleToUser()||!n.isEnabled())continue;
   if(!pkg.isEmpty()&&!pkg.equals(s(n.getPackageName())))continue;
   String got=field.equals("text")?s(n.getText()):field.equals("description")?s(n.getContentDescription()):field.equals("class")?s(n.getClassName()):"";
   if(value.equals(got)){hit=n;count++;}
  }
  if(count!=1)throw new IllegalStateException("Expected unique selector; matches="+count+" field="+field+" value="+value);
  return hit;
 }
 static boolean clickNode(AccessibilityNodeInfo n){
  while(n!=null&&!n.isClickable())n=n.getParent();
  return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
 }
 static boolean clickFirstDescription(String pkg,String... values){
  for(AccessibilityNodeInfo n:allNodes()){
   if(!n.isVisibleToUser()||!n.isEnabled()||!pkg.equals(s(n.getPackageName())))continue;
   String d=s(n.getContentDescription());
   for(String v:values)if(v.equalsIgnoreCase(d)&&clickNode(n))return true;
  }
  return false;
 }
 static AccessibilityNodeInfo firstEditable(String pkg){
  for(AccessibilityNodeInfo n:allNodes())
   if(n.isVisibleToUser()&&n.isEnabled()&&n.isEditable()&&pkg.equals(s(n.getPackageName())))return n;
  throw new IllegalStateException("No editable field for "+pkg);
 }
 static String visibleReply(String pkg,String prompt){
  ArrayList<String> parts=new ArrayList<>();
  for(AccessibilityNodeInfo n:allNodes()){
   if(!n.isVisibleToUser()||!pkg.equals(s(n.getPackageName())))continue;
   String t=s(n.getText()).trim(); if(t.isEmpty()||t.equals(prompt))continue;
   Rect r=new Rect();n.getBoundsInScreen(r);
   if(r.left>140)continue;
   String lo=t.toLowerCase(Locale.ROOT);
   if(lo.equals("working")||lo.equals("show more")||lo.equals("retry")||lo.equals("copy")||lo.equals("good response")||lo.equals("bad response"))continue;
   if(!parts.contains(t))parts.add(t);
  }
  return String.join("\n",parts).trim();
 }
 public static void main(String[] args)throws Exception{
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
  display=Integer.parseInt(args[0]); if(display==0)throw new IllegalArgumentException("Physical display prohibited");
  String op=args.length>1?args[1]:"dump";
  HandlerThread ht=new HandlerThread("ghost-ui");ht.start();
  try{
   Class<?> ic=Class.forName("android.app.IUiAutomationConnection");
   Object conn=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
   ui=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,ic).newInstance(ht.getLooper(),conn);
   UiAutomation.class.getMethod("connect",int.class).invoke(ui,1);
   AccessibilityServiceInfo si=ui.getServiceInfo();si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(si);
   if(op.equals("ask")){
    String pkg=args[2], prompt=args[3];
    clickFirstDescription(pkg,"New chat","Start new chat");
    Thread.sleep(250);
    AccessibilityNodeInfo edit=firstEditable(pkg);
    Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prompt);
    if(!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b))throw new IllegalStateException("set text rejected");
    Thread.sleep(180);
    if(!clickFirstDescription(pkg,"Send","Send message","Submit"))throw new IllegalStateException("Send control not found");
    String last="",candidate=""; long changed=System.currentTimeMillis(), deadline=changed+120000;
    while(System.currentTimeMillis()<deadline){
     Thread.sleep(180);
     String now=visibleReply(pkg,prompt);
     if(!now.equals(last)){last=now;changed=System.currentTimeMillis();}
     String lo=now.toLowerCase(Locale.ROOT);
     boolean useful=!now.isEmpty()&&!lo.equals("working")&&!lo.endsWith("working");
     if(useful)candidate=now;
     if(!candidate.isEmpty()&&System.currentTimeMillis()-changed>700)break;
    }
    if(candidate.isEmpty())throw new IllegalStateException("No stable model reply");
    System.out.println(new JSONObject().put("text",candidate).toString());
   } else if(op.equals("dump")){
    JSONArray arr=new JSONArray();
    for(AccessibilityNodeInfo n:allNodes()){
     Rect r=new Rect();n.getBoundsInScreen(r);
     arr.put(new JSONObject().put("text",s(n.getText())).put("description",s(n.getContentDescription())).put("class",s(n.getClassName())).put("package",s(n.getPackageName())).put("editable",n.isEditable()).put("visible",n.isVisibleToUser()).put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom})));
    }
    System.out.println(new JSONObject().put("display",display).put("nodes",arr).toString());
   } else throw new IllegalArgumentException("Unknown operation");
  } finally {if(ui!=null)try{UiAutomation.class.getMethod("disconnect").invoke(ui);}catch(Exception e){} ht.quitSafely();}
 }
}
