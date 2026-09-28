import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.*;
import android.util.SparseArray;
import android.view.accessibility.*;
import android.graphics.Rect;
import org.json.*;
import java.util.*;

public class GhostUI {
 static UiAutomation ui; static int display;
 static String s(CharSequence x){return x==null?"":x.toString();}
 static List<AccessibilityWindowInfo> windows(){
  SparseArray<List<AccessibilityWindowInfo>> a=ui.getWindowsOnAllDisplays();
  List<AccessibilityWindowInfo> w=a.get(display); return w==null?Collections.emptyList():w;
 }
 static boolean clickNode(AccessibilityNodeInfo n){
  while(n!=null&&!n.isClickable())n=n.getParent();
  return n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
 }
 static boolean clickDesc(AccessibilityNodeInfo n,String pkg,String[] vals,int depth){
  if(n==null||depth>70)return false;
  if(n.isVisibleToUser()&&n.isEnabled()&&pkg.equals(s(n.getPackageName()))){
   String d=s(n.getContentDescription());
   for(String v:vals)if(v.equalsIgnoreCase(d)&&clickNode(n))return true;
  }
  for(int i=0;i<n.getChildCount();i++){
   AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;
   boolean hit=false;try{hit=clickDesc(c,pkg,vals,depth+1);}finally{c.recycle();}
   if(hit)return true;
  }
  return false;
 }
 static boolean clickFirstDescription(String pkg,String... vals){
  for(AccessibilityWindowInfo w:windows()){
   AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
   boolean hit=false;try{hit=clickDesc(r,pkg,vals,0);}finally{r.recycle();}
   if(hit)return true;
  }
  return false;
 }
 static boolean setEditable(AccessibilityNodeInfo n,String pkg,String prompt,int depth){
  if(n==null||depth>70)return false;
  if(n.isVisibleToUser()&&n.isEnabled()&&n.isEditable()&&pkg.equals(s(n.getPackageName()))){
   Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prompt);
   return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);
  }
  for(int i=0;i<n.getChildCount();i++){
   AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;
   boolean hit=false;try{hit=setEditable(c,pkg,prompt,depth+1);}finally{c.recycle();}
   if(hit)return true;
  }
  return false;
 }
 static boolean setFirstEditable(String pkg,String prompt){
  for(AccessibilityWindowInfo w:windows()){
   AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
   try{if(setEditable(r,pkg,prompt,0))return true;}finally{r.recycle();}
  }
  return false;
 }
 static void collectReply(AccessibilityNodeInfo n,String pkg,String prompt,LinkedHashSet<String> out,int depth){
  if(n==null||depth>70||out.size()>250)return;
  if(n.isVisibleToUser()&&pkg.equals(s(n.getPackageName()))){
   String t=s(n.getText()).trim();
   if(!t.isEmpty()&&!t.equals(prompt)){
    Rect r=new Rect();n.getBoundsInScreen(r);
    String lo=t.toLowerCase(Locale.ROOT);
    if(r.left<=140&&!lo.equals("working")&&!lo.equals("thinking")&&!lo.equals("show more")&&!lo.equals("retry")&&!lo.equals("copy")&&!lo.equals("good response")&&!lo.equals("bad response"))out.add(t);
   }
  }
  for(int i=0;i<n.getChildCount();i++){
   AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;
   try{collectReply(c,pkg,prompt,out,depth+1);}finally{c.recycle();}
  }
 }
 static String visibleReply(String pkg,String prompt){
  LinkedHashSet<String> out=new LinkedHashSet<>();
  for(AccessibilityWindowInfo w:windows()){
   AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;
   try{collectReply(r,pkg,prompt,out,0);}finally{r.recycle();}
  }
  return String.join("\n",out).trim();
 }
 public static void main(String[] args)throws Exception{
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
  display=Integer.parseInt(args[0]);if(display==0)throw new IllegalArgumentException("Physical display prohibited");
  String op=args.length>1?args[1]:"ask";
  HandlerThread ht=new HandlerThread("ghost-ui-fast");ht.start();
  try{
   Class<?> ic=Class.forName("android.app.IUiAutomationConnection");
   Object conn=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
   ui=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,ic).newInstance(ht.getLooper(),conn);
   UiAutomation.class.getMethod("connect",int.class).invoke(ui,1);
   AccessibilityServiceInfo si=ui.getServiceInfo();si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(si);
   if(!op.equals("ask"))throw new IllegalArgumentException("Unknown operation");
   String pkg=args[2],prompt=args[3];
   clickFirstDescription(pkg,"New chat","Start new chat");Thread.sleep(200);
   if(!setFirstEditable(pkg,prompt))throw new IllegalStateException("set text rejected");
   Thread.sleep(120);
   if(!clickFirstDescription(pkg,"Send","Send message","Submit"))throw new IllegalStateException("Send control not found");
   String last="",candidate="";long changed=System.currentTimeMillis(),deadline=changed+120000;
   while(System.currentTimeMillis()<deadline){
    Thread.sleep(150);String now=visibleReply(pkg,prompt);
    if(!now.equals(last)){last=now;changed=System.currentTimeMillis();}
    if(!now.isEmpty())candidate=now;
    if(!candidate.isEmpty()&&System.currentTimeMillis()-changed>650)break;
   }
   if(candidate.isEmpty())throw new IllegalStateException("No stable model reply");
   System.out.println(new JSONObject().put("text",candidate).toString());
  }finally{if(ui!=null)try{UiAutomation.class.getMethod("disconnect").invoke(ui);}catch(Exception e){}ht.quitSafely();}
 }
}
