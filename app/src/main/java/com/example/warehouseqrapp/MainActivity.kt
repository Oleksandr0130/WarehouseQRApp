package com.example.warehouseqrapp

import android.Manifest
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* isGranted */ _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрос runtime-разрешения камеры (Android 6+)
        ensureCameraPermission()

        setContent {
            WarehouseQRAppTheme {
                WebViewScreen("https://warehouse-qr-app-8adwv.ondigitalocean.app/")
            }
        }
    }

    private fun ensureCameraPermission() {
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
                // Базовые клиенты
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    // ВАЖНО: grant только из UI-потока, иначе запрос может «потеряться» в WebView
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request ?: return
                        (context as? ComponentActivity)?.runOnUiThread {
                            // Разрешаем только видеозахват
                            val needsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            if (needsVideo) {
                                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                            } else {
                                request.deny()
                            }
                        }
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

                    // Обновляем стили/скрипты без кэша, чтобы правки подтягивались корректно
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // (не обязательно, но удобно) убираем метку "wv" из UA
                    userAgentString = userAgentString.replace("wv", "")
                }

                // Чистим кэш/историю и включаем аппаратное ускорение
                clearCache(true)
                clearHistory()
                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

                // Загружаем приложение (добавляем параметр, чтобы наверняка обойти кэш прокси/сети)
                val sep = if (url.contains("?")) "&" else "?"
                loadUrl(url + sep + "v=" + System.currentTimeMillis())
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
