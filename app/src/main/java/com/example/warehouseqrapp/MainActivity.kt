package com.example.warehouseqrapp

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.warehouseqrapp.ui.theme.WarehouseQRAppTheme
import android.graphics.Color as AColor
import android.view.View as AView

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // фикс портретной ориентации
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // прозрачные панели
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = AColor.TRANSPARENT
        }

        // верх остаётся видимым; низ прячем
        val controller = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        checkCameraPermission()

        setContent {
            WarehouseQRAppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor(0xFF0A1F2B))
                        // ВАЖНО: Отступ сверху под статус-бар + снизу под клавиатуру
                        .windowInsetsPadding(
                            WindowInsets.statusBars.union(WindowInsets.ime)
                        )
                ) {
                    WebViewScreen("https://warehouse-qr-app-8adwv.ondigitalocean.app")
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Открыть URL во внешнем браузере через Chrome Custom Tabs */
    private fun openCustomTab(url: String) {
        try {
            val intent = CustomTabsIntent.Builder().build()
            intent.launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    @Composable
    fun WebViewScreen(url: String) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // прозрачный фон, скролл
                    setBackgroundColor(AColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    overScrollMode = AView.OVER_SCROLL_IF_CONTENT_SCROLLS

                    // cookies
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_NO_CACHE

                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)

                        // «маскируем» WebView
                        userAgentString = userAgentString.replace("wv", "")
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest
                        ): Boolean {
                            val u = request.url
                            val host = u.host ?: ""

                            val openExternally =
                                host.endsWith("checkout.stripe.com") ||
                                        host.contains("pay.google.com") ||
                                        host.contains("payments.google") ||
                                        host.contains("giropay") ||
                                        host.contains("sofort") ||
                                        host.contains("paypal.com")

                            return if (openExternally) {
                                openCustomTab(u.toString())
                                true
                            } else {
                                false
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val tempWebView = WebView(view?.context!!)
                            tempWebView.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let { openCustomTab(it) }
                                }
                            }
                            transport.webView = tempWebView
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    clearCache(true)
                    clearHistory()
                    setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

                    loadUrl(url + (if (url.contains("?")) "&" else "?") + "v=" + System.currentTimeMillis())
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
