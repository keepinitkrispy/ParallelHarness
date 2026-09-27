import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.*;
import android.util.SparseArray;
import android.view.accessibility.*;
import android.graphics.Rect;
import org.json.*;
import java.util.*;
public class GhostUI {
 static JSONArray nodes=new JSONArray();
 static ArrayList<AccessibilityNodeInfo> matches=new ArrayList<>();
 static String field="", value="", pkg="";
 static String s(CharSequence x){return x==null?"":x.toString();}
 static void walk(AccessibilityNodeInfo n,String path,int depth)throws Exception{
  if(n==null||depth>60||nodes.length()>3000)return;
  Rect r=new Rect();n.getBoundsInScreen(r);
  JSONObject o=new JSONObject().put("path",path).put("text",s(n.getText())).put("description",s(n.getContentDescription())).put("id",s(n.getViewIdResourceName())).put("class",s(n.getClassName())).put("package",s(n.getPackageName())).put("clickable",n.isClickable()).put("editable",n.isEditable()).put("visible",n.isVisibleToUser()).put("enabled",n.isEnabled()).put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}));
  nodes.put(o);
  if(!field.isEmpty()&&value.equals(o.optString(field))&&n.isVisibleToUser()&&n.isEnabled()&&(pkg.isEmpty()||pkg.equals(s(n.getPackageName()))))matches.add(n);
  for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),path+"/"+i,depth+1);
 }
 public static void main(String[] args)throws Exception{
  if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
  int display=Integer.parseInt(args[0]);if(display==0)throw new IllegalArgumentException("Physical display prohibited");
  String op=args.length>1?args[1]:"dump";
  if(args.length>3){field=args[2];value=args[3];}if(args.length>4)pkg=args[4];
  HandlerThread ht=new HandlerThread("ghost-ui");ht.start();UiAutomation ui=null;
  try{
   Class<?> ic=Class.forName("android.app.IUiAutomationConnection");
   Object conn=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
   ui=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,ic).newInstance(ht.getLooper(),conn);
   UiAutomation.class.getMethod("connect",int.class).invoke(ui,1);
   AccessibilityServiceInfo si=ui.getServiceInfo();si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(si);
   SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
   List<AccessibilityWindowInfo> windows=all.get(display);JSONArray ws=new JSONArray();
   if(windows!=null)for(AccessibilityWindowInfo w:windows){ws.put(new JSONObject().put("id",w.getId()).put("title",s(w.getTitle())).put("display",w.getDisplayId()));walk(w.getRoot(),"w"+w.getId(),0);}
   JSONObject out=new JSONObject().put("display",display).put("windows",ws).put("nodes",nodes).put("matches",matches.size());
   if(!op.equals("dump")){
    if(matches.size()!=1)throw new IllegalStateException("Expected unique selector; matches="+matches.size());
    AccessibilityNodeInfo n=matches.get(0);boolean ok=false;
    if(op.equals("click")){while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null)ok=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    else if(op.equals("set-text")){Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args[5]);ok=n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}
    else throw new IllegalArgumentException("Unknown operation");
    out.put("action",op).put("accepted",ok);out.remove("nodes");if(!ok)throw new IllegalStateException("Action rejected");
   }
   System.out.println(out.toString());
  }finally{if(ui!=null)try{UiAutomation.class.getMethod("disconnect").invoke(ui);}catch(Exception e){}ht.quitSafely();}
 }
}
