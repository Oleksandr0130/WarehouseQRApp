package com.example.warehouseqrapp

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.warehouseqrapp.ui.theme.WarehouseQRAppTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                // Тут можно показать тост или диалог о том, что камера нужна
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Жёстко блокируем автоповорот для всей Activity (дублирует настройку из манифеста)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        checkCameraPermission()

        setContent {
            WarehouseQRAppTheme {
                WebViewScreen("https://warehouse-qr-app-8adwv.ondigitalocean.app/api/")
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
}

@Composable
fun WebViewScreen(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        // Разрешаем запрошенные ресурсы (как в исходнике)
                        request?.grant(request.resources)
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowContentAccess = true
                    allowFileAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // ключевое: отключаем кэш, чтобы забрать свежие CSS
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // (опционально) userAgent без "wv"
                    userAgentString = userAgentString.replace("wv", "")
                }

                // Сброс любого накопленного кэша/истории
                clearCache(true)
                clearHistory()
                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

                loadUrl(url + (if (url.contains("?")) "&" else "?") + "v=" + System.currentTimeMillis())
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
