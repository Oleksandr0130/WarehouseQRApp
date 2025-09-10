package com.flowqr.flowqr

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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.billingclient.api.*
import com.flowqr.flowqr.ui.theme.WarehouseQRAppTheme
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.graphics.Color as AColor
import android.view.View as AView

class MainActivity : ComponentActivity(), PurchasesUpdatedListener {

    // ====== CONFIG ======
    private val PRODUCT_ID = "flowqr_standard"
    private val VERIFY_URL =
        "https://warehouse-qr-app-8adwv.ondigitalocean.app/api/billing/play/verify"
    // =====================

    private val bg = Executors.newSingleThreadExecutor()

    // Billing
    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null

    // JWT из WebView (через JS-мост)
    @Volatile private var authHeader: String? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = AColor.TRANSPARENT
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        checkCameraPermission()

        // init Billing
        initBillingClient()

        setContent {
            WarehouseQRAppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0xFF0A1F2B))
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.ime))
                ) {
                    WebViewScreen("https://warehouse-qr-app-8adwv.ondigitalocean.app/app/account")
                }
            }
        }
    }

    // ---------------- Billing setup ----------------

    private fun initBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .enablePendingPurchases()
            .setListener(this)
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails(PRODUCT_ID)
                    // восстановим доступ, если уже куплено
                    restoreEntitlement()
                } else {
                    toast("Billing not ready: ${result.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {
                // можно добавить ретрай
            }
        })
    }

    private fun queryProductDetails(productId: String) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList).build()

        billingClient.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = detailsList.firstOrNull()
                if (productDetails == null) toast("Product not found: $productId")
            } else {
                toast("Failed to load product: ${result.debugMessage}")
            }
        }
    }

    private fun launchPurchase(productId: String) {
        val details = productDetails ?: run {
            toast("Product details not loaded")
            queryProductDetails(productId)
            return
        }
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            toast("No offer token (check base plan/offers in Play Console)")
            return
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            ).build()
        billingClient.launchBillingFlow(this, flowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            verifyOnBackend(purchase.purchaseToken, PRODUCT_ID) { ok ->
                                if (ok) {
                                    acknowledgeIfNeeded(purchase)
                                    toast("Subscription activated")
                                } else {
                                    toast("Verification failed")
                                }
                            }
                        }
                        Purchase.PurchaseState.PENDING -> {
                            toast("Payment pending. You'll get access after it completes.")
                        }
                        else -> {}
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> toast("Purchase canceled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // попробуем восстановить
                restoreEntitlement()
            }
            else -> toast("Purchase error: ${result.debugMessage}")
        }
    }

    private fun restoreEntitlement() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchasesList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchasesList.forEach { p ->
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    verifyOnBackend(p.purchaseToken, PRODUCT_ID) { ok ->
                        if (ok) acknowledgeIfNeeded(p)
                    }
                }
            }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken).build()
        billingClient.acknowledgePurchase(params) { br ->
            if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                toast("Acknowledge failed: ${br.debugMessage}")
            }
        }
    }

    private fun verifyOnBackend(
        purchaseToken: String,
        productId: String,
        callback: (Boolean) -> Unit
    ) {
        bg.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(VERIFY_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    authHeader?.let { setRequestProperty("Authorization", it) } // ✅ JWT
                }
                val body =
                    """{"productId":"$productId","purchaseToken":"$purchaseToken","packageName":"$packageName"}"""
                BufferedOutputStream(conn.outputStream).use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val ok = code in 200..299
                if (ok) {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
                        while (br.readLine() != null) { /* no-op */ }
                    }
                }
                runOnUiThread { callback(ok) }
            } catch (_: Exception) {
                runOnUiThread { callback(false) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ---------------- WebView & JS Bridge ----------------

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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
                    setBackgroundColor(AColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    overScrollMode = AView.OVER_SCROLL_IF_CONTENT_SCROLLS

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
                        userAgentString = userAgentString.replace("wv", "") + " FlowQRApp/Android"
                    }

                    // JS Bridge
                    addJavascriptInterface(BillingJsBridge(this@MainActivity), "billing")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest
                        ): Boolean {
                            val host = request.url.host ?: ""
                            val isPayment =
                                host.endsWith("checkout.stripe.com") ||
                                        host.contains("pay.google.com") ||
                                        host.contains("payments.google") ||
                                        host.contains("giropay") ||
                                        host.contains("sofort") ||
                                        host.contains("paypal.com")
                            return if (isPayment) {
                                Toast.makeText(context, "Manage your subscription on the website.", Toast.LENGTH_SHORT).show()
                                true
                            } else false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // ⚙️ вытащим JWT из localStorage и передадим в мост
                            // если токена нет — метод просто ничего не сделает
                            val js = """
                                (function(){
                                  try {
                                    var t = localStorage.getItem('accessToken') || localStorage.getItem('token');
                                    if (t && !t.startsWith('Bearer ')) t = 'Bearer ' + t;
                                    if (window.billing && t) { window.billing.setAuth(t); }
                                  } catch(e) {}
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
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

                    val fullUrl = url +
                            (if (url.contains("?")) "&" else "?") +
                            "v=" + System.currentTimeMillis() +
                            "&source=android_app"
                    loadUrl(fullUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ===== JS-мост: window.billing.buy(...), window.billing.setAuth('Bearer ...') =====
    @Keep
    inner class BillingJsBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun buy(productId: String) {
            activity.runOnUiThread {
                launchPurchase(productId.ifBlank { PRODUCT_ID })
            }
        }
        @JavascriptInterface
        fun setAuth(bearer: String) {
            authHeader = bearer
        }
    }
}
