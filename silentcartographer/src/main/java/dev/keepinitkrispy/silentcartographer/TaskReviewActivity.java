package dev.keepinitkrispy.silentcartographer;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class TaskReviewActivity extends Activity {
  private void unavailable(WebView view) {
    String task=getIntent().getStringExtra("task");
    String detail=getIntent().getStringExtra("detail");
    String message="<h1>Task review unavailable</h1>"
        +"<p>The local task service is not responding.</p>"
        +"<p>"+android.text.TextUtils.htmlEncode(task==null?"Task":task)+"</p>"
        +"<p>"+android.text.TextUtils.htmlEncode(detail==null?"":detail)+"</p>";
    view.loadDataWithBaseURL(null,message,"text/html","UTF-8",null);
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    String token=getIntent().getStringExtra("token");
    WebView web=new WebView(this);
    web.setWebViewClient(new WebViewClient() {
      @Override public void onReceivedError(
          WebView v, WebResourceRequest req, WebResourceError err) {
        if(req.isForMainFrame()) unavailable(v);
      }

      @Override public void onReceivedHttpError(
          WebView v, WebResourceRequest req, WebResourceResponse response) {
        if(req.isForMainFrame() && response.getStatusCode()>=400) unavailable(v);
      }
    });
    web.getSettings().setJavaScriptEnabled(false);
    setContentView(web);

    if(token==null) {
      web.loadUrl("http://127.0.0.1:49173/active");
      return;
    }
    if(!token.matches("[0-9a-f]{32}")) {
      finish();
      return;
    }
    web.loadUrl("http://127.0.0.1:49173/task/"+token);
  }
}
