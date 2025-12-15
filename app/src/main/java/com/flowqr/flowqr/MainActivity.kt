package com.flowqr.flowqr

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.*
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

    // ================== ✅ CHANGED CONFIG ==================
    // Это subscriptionId из Play Console (название/идентификатор подписки)
    private val SUBSCRIPTION_ID = "flowqr_standard"

    // Это basePlanId (не productId!) — ровно как у тебя в Play Console
    private val BASE_PLAN_MONTH = "basic-monthly"
    private val BASE_PLAN_3MONTH = "basic-3months"
    private val BASE_PLAN_YEAR = "basic-year"

    private val VERIFY_URL =
        "https://warehouse-qr-app-8adwv.ondigitalocean.app/api/billing/play/verify"
    // =======================================================

    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var billingClient: BillingClient

    // ✅ CHANGED: теперь мы храним ProductDetails ТОЛЬКО для flowqr_standard
    private var subscriptionDetails: ProductDetails? = null

    // ✅ NEW: сюда будем запоминать какой basePlan выбрал пользователь
    @Volatile private var pendingBasePlanId: String? = null

    // JWT из WebView (через JS-мост)
    @Volatile private var authHeader: String? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- очистка cookies ----------
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AColor.TRANSPARENT
        window.navigationBarColor = AColor.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        checkCameraPermission()
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

    // ================= BILLING =================

    private fun initBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .enablePendingPurchases()
            .setListener(this)
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    // ✅ CHANGED: грузим ТОЛЬКО одну подписку
                    querySubscriptionDetails()
                    restoreEntitlement()
                } else {
                    toast("Billing not ready: ${result.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    // ✅ CHANGED: загрузка ProductDetails для flowqr_standard
    private fun querySubscriptionDetails() {
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUBSCRIPTION_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                subscriptionDetails = list.firstOrNull()
                if (subscriptionDetails == null) {
                    toast("Subscription not found: $SUBSCRIPTION_ID")
                }
            } else {
                toast("Failed to load subscription: ${result.debugMessage}")
            }
        }
    }

    // ✅ CHANGED: покупка по basePlanId (1/3/12), но продукт всегда flowqr_standard
    private fun launchPurchaseByBasePlan(basePlanId: String) {
        val details = subscriptionDetails ?: run {
            toast("Subscription details not loaded")
            querySubscriptionDetails()
            return
        }

        // ✅ NEW: находим offerToken по basePlanId
        val offer = details.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId }

        if (offer == null) {
            toast("Offer not found for basePlanId=$basePlanId. Check Play Console base plans.")
            return
        }

        pendingBasePlanId = basePlanId // ✅ NEW

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .build()

        billingClient.launchBillingFlow(this, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            // ✅ CHANGED: на бэкенд отправляем subscriptionId, НЕ basePlanId
                            verifyOnBackend(purchase.purchaseToken, SUBSCRIPTION_ID) { ok ->
                                if (ok) {
                                    acknowledgeIfNeeded(purchase)
                                    toast("Subscription activated")
                                } else {
                                    toast("Verification failed")
                                }
                            }
                        }
                        Purchase.PurchaseState.PENDING -> toast("Payment pending")
                        else -> {}
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> toast("Purchase canceled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restoreEntitlement()
            else -> toast("Purchase error: ${result.debugMessage}")
        }
    }

    private fun restoreEntitlement() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            list.forEach { p ->
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    // ✅ CHANGED: верифицируем именно flowqr_standard
                    verifyOnBackend(p.purchaseToken, SUBSCRIPTION_ID) { ok ->
                        if (ok) acknowledgeIfNeeded(p)
                    }
                }
            }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { br ->
            if (br.responseCode != BillingClient.BillingResponseCode.OK) {
                toast("Acknowledge failed: ${br.debugMessage}")
            }
        }
    }

    // ✅ CHANGED: productId тут должен быть subscriptionId = flowqr_standard
    private fun verifyOnBackend(
        purchaseToken: String,
        productId: String,
        callback: (Boolean) -> Unit
    ) {
        bg.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(VERIFY_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    authHeader?.let { setRequestProperty("Authorization", it) }
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

    // ================= WEBVIEW =================

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
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

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
                                        host.contains("paypal.com")
                            return if (isPayment) {
                                Toast.makeText(context, "Manage your subscription on the website.", Toast.LENGTH_SHORT).show()
                                true
                            } else false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
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

    // ===== JS-мост: window.billing.buy('basic-monthly' | 'basic-3months' | 'basic-year') =====
    @Keep
    inner class BillingJsBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun buy(basePlanId: String) {
            activity.runOnUiThread {
                // ✅ CHANGED: basePlanId → offerToken, product всегда flowqr_standard
                val chosen = when (basePlanId) {
                    BASE_PLAN_MONTH, BASE_PLAN_3MONTH, BASE_PLAN_YEAR -> basePlanId
                    else -> BASE_PLAN_MONTH // дефолт
                }
                launchPurchaseByBasePlan(chosen)
            }
        }

        @JavascriptInterface
        fun setAuth(bearer: String) {
            authHeader = bearer
        }
    }
}
