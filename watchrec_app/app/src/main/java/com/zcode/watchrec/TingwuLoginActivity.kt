package com.zcode.watchrec

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Official browser login, no JavaScript bridge and no credentials passed through intents. */
class TingwuLoginActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var checking = false
    private var closed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(0, 22, 0, 25) }
        status = TextView(this).apply { text = "听悟官方登录 · 手机扫码"; setTextColor(Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(-1, 36))
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = TingwuSession.USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    return uri.scheme != "https" || uri.userInfo != null || (request.isForMainFrame && uri.host !in setOf("tingwu.aliyun.com", "account.aliyun.com", "login.aliyun.com", "passport.aliyun.com", "login.taobao.com"))
                }
                override fun onPageFinished(view: WebView, url: String) { checkSession(); handler.postDelayed({ showOfficialLogin() }, 1_500) }
            }
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(browser, true) }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER }
        listOf("返回" to { finish() }, "登录" to { showOfficialLogin() }, "检查登录" to { checkSession() }).forEach { (label, action) ->
            buttons.addView(Button(this).apply { text = label; textSize = 10f; setPadding(0,0,0,0); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, 62, 1f))
        }
        root.addView(buttons, LinearLayout.LayoutParams(-1, 62).apply { setMargins(60, 0, 60, 0) })
        setContentView(root)
        browser.loadUrl("${TingwuSession.ORIGIN}/register/login?tw_redirect=${Uri.encode(TingwuSession.ORIGIN + "/home")}")
        handler.postDelayed(object : Runnable { override fun run() { if (!closed) { prepareQrLayout(); checkSession(); handler.postDelayed(this, 4_000) } } }, 4_000)
    }

    private fun showOfficialLogin() {
        if (closed || TingwuSession.connected.value || Uri.parse(browser.url.orEmpty()).host != "tingwu.aliyun.com") return
        browser.evaluateJavascript("""(function(){
          var nodes=document.querySelectorAll('button,a,span,div');
          for(var i=0;i<nodes.length;i++) if(nodes[i].textContent.trim()==='立即登录'){nodes[i].click();return true;}
          return false;
        })()""".trimIndent(), null)
        handler.postDelayed({ prepareQrLayout() }, 1_000)
    }

    private fun prepareQrLayout() {
        if (closed || Uri.parse(browser.url.orEmpty()).host != "tingwu.aliyun.com") return
        // Crop the official embedded desktop login to its QR panel; don't replace its login flow.
        browser.evaluateJavascript("""(function(){
          if(document.getElementById('wanlu-qr-layout'))return;
          var s=document.createElement('style');s.id='wanlu-qr-layout';
          s.textContent='.aliyun-login-wrapper{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;margin:0!important;transform:none!important;overflow:hidden!important;border-radius:0!important}.aliyun-login-wrapper .fl-dialog-body{width:100%!important;height:100%!important;padding:0!important;overflow:hidden!important}#aliyun-login-box-wrapper{position:absolute!important;left:-9px!important;top:-89px!important;width:782px!important;height:518px!important;transform:scale(.7)!important;transform-origin:0 0!important}.aliyun-login-wrapper .fl-dialog-close{display:none!important}';
          document.head.appendChild(s);
        })()""".trimIndent(), null)
    }

    private fun checkSession() {
        if (checking || closed) return
        checking = true
        thread(name = "tingwu-login-check", isDaemon = true) {
            val verified = runCatching { TingwuSession.verify() }.getOrDefault(false)
            handler.post {
                checking = false
                if (!closed && verified) { CookieManager.getInstance().flush(); status.text = "听悟账号已连接"; handler.postDelayed({ if (!closed) finish() }, 800) }
            }
        }
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        browser.stopLoading(); browser.destroy()
        super.onDestroy()
    }
}
