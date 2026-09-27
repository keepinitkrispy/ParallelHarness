package dev.keepinitkrispy.silentcartographer;

import android.app.*;
import android.content.*;
import java.net.HttpURLConnection;
import java.net.URL;

public final class TaskStatusReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context ctx, Intent intent) {
    String token=intent.getStringExtra("token");
    String task=intent.getStringExtra("task");
    String detail=intent.getStringExtra("detail");
    String state=intent.getStringExtra("state");
    if (token==null || !token.matches("[0-9a-f]{32}") ||
        task==null || detail==null || state==null) return;
    PendingResult pending=goAsync();
    new Thread(() -> {
      try {
        HttpURLConnection c=(HttpURLConnection)new URL(
            "http://127.0.0.1:49173/validate/"+token).openConnection();
        c.setConnectTimeout(1000); c.setReadTimeout(1000);
        if(c.getResponseCode()!=204) return;
        show(ctx,token,task,detail,state);
      } catch(Exception ignored) {
      } finally { pending.finish(); }
    },"task-notification-validator").start();
  }

  private static void show(Context ctx,String token,String task,String detail,String state) {
    String prefix;
    if ("start".equals(state)) prefix="Started";
    else if ("milestone".equals(state)) prefix="Updated";
    else if ("done".equals(state)) prefix="Completed";
    else if ("failed".equals(state)) prefix="Needs attention";
    else return;

    NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel(
        "task_updates","Task updates",NotificationManager.IMPORTANCE_DEFAULT));
    int id=token.hashCode() & 0x7fffffff;
    Intent open=new Intent(ctx,TaskReviewActivity.class)
        .putExtra("token",token).putExtra("task",task).putExtra("detail",detail)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent pi=PendingIntent.getActivity(ctx,id,open,
        PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification note=new Notification.Builder(ctx,"task_updates")
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle(prefix+": "+task.substring(0,Math.min(70,task.length())))
        .setContentText(detail.substring(0,Math.min(500,detail.length())))
        .setStyle(new Notification.BigTextStyle().bigText(detail))
        .setContentIntent(pi)
        .addAction(new Notification.Action.Builder(
            android.R.drawable.ic_menu_view,"Review / Control",pi).build())
        .setAutoCancel(true).build();
    nm.notify(id,note);
  }
}
