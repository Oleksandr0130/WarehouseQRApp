package com.example.warehouseqrapp

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.warehouseqrapp.ui.theme.WarehouseQRAppTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // фикс портретной ориентации
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // ★ Edge-to-edge: разрешаем контенту заходить под системные бары
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ★ Делает статус- и навигационную панель прозрачными
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // ★ Светлые иконки/текст в барах при светлой теме (или выключи, если у тебя тёмная)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        // ★ На Android 10+ убираем «контрастную подложку» навбар
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        checkCameraPermission()

        setContent {
            WarehouseQRAppTheme {
                WebViewScreen("https://warehouse-qr-app-8adwv.ondigitalocean.app")
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
                    // ★ WebView тоже прозрачный, чтобы фон сайта был виден под барами
                    setBackgroundColor(Color.TRANSPARENT)

                    // cookies (для GPay/редиректов)
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

                        // чуть "маскируем" WebView
                        userAgentString = userAgentString.replace("wv", "")
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest
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
                            } else false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
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

                    // Чистый старт
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
