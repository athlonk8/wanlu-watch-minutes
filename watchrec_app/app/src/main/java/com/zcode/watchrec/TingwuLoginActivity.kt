package com.zcode.watchrec

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(0, dp(14), 0, dp(28)) }
        status = TextView(this).apply { text = "听悟官方登录 · 手机扫码"; setTextColor(Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(23)))
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
            buttons.addView(Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false
                setTextColor(Color.rgb(16, 61, 50)); setPadding(0, 0, 0, dp(3))
                background = glossyBackground()
                elevation = dp(5).toFloat()
                val button = this
                stateListAnimator = StateListAnimator().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofFloat(button, "translationY", dp(2).toFloat()).setDuration(80))
                    addState(intArrayOf(), ObjectAnimator.ofFloat(button, "translationY", 0f).setDuration(100))
                }
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(buttons, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(dp(29), dp(4), dp(29), 0) })
        setContentView(root)
        browser.loadUrl("${TingwuSession.ORIGIN}/register/login?tw_redirect=${Uri.encode(TingwuSession.ORIGIN + "/home")}")
        handler.postDelayed(object : Runnable { override fun run() { if (!closed) { prepareQrLayout(); checkSession(); handler.postDelayed(this, 4_000) } } }, 4_000)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun glossyBackground(): StateListDrawable {
        fun face(pressed: Boolean): LayerDrawable {
            val edge = GradientDrawable().apply { setColor(Color.rgb(25, 119, 95)); cornerRadius = dp(23).toFloat() }
            val surface = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(215, 255, 233), Color.rgb(138, 241, 197), Color.rgb(67, 197, 158))).apply {
                cornerRadius = dp(23).toFloat(); setStroke(dp(1), Color.argb(95, 255, 255, 255))
            }
            val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(130, 255, 255, 255), Color.argb(8, 255, 255, 255))).apply { cornerRadius = dp(18).toFloat() }
            return LayerDrawable(arrayOf(edge, surface, gloss)).apply {
                setLayerInset(1, 0, if (pressed) dp(2) else 0, 0, if (pressed) dp(1) else dp(4))
                setLayerInset(2, dp(4), dp(3), dp(4), dp(25))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(true))
            addState(intArrayOf(), face(false))
        }
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
