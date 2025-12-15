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

    // ================== 🔧 CHANGED CONFIG ==================
    private val PRODUCT_IDS = listOf(
        "basic-monthly",
        "basic-3months",
        "basic-year"
    )

    private val VERIFY_URL =
        "https://warehouse-qr-app-8adwv.ondigitalocean.app/api/billing/play/verify"
    // =======================================================

    private val bg = Executors.newSingleThreadExecutor()

    // Billing
    private lateinit var billingClient: BillingClient

    // 🆕 productId -> ProductDetails
    private val productDetailsMap = mutableMapOf<String, ProductDetails>()

    @Volatile private var authHeader: String? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- очистка cookies ----
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
                    queryAllProducts() // 🔧 CHANGED
                    restoreEntitlement()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    // 🆕 загрузка ВСЕХ подписок
    private fun queryAllProducts() {
        val products = PRODUCT_IDS.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                list.forEach { pd ->
                    productDetailsMap[pd.productId] = pd
                }
            } else {
                toast("Failed to load subscriptions")
            }
        }
    }

    // 🔧 CHANGED: покупка конкретного плана
    private fun launchPurchase(productId: String) {
        val details = productDetailsMap[productId]
        if (details == null) {
            toast("Subscription not loaded: $productId")
            return
        }

        val offer = details.subscriptionOfferDetails?.firstOrNull()
        if (offer == null) {
            toast("No offer for $productId")
            return
        }

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

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return

        purchases?.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                val productId = purchase.products.firstOrNull() ?: return@forEach

                verifyOnBackend(purchase.purchaseToken, productId) { ok ->
                    if (ok) acknowledgeIfNeeded(purchase)
                }
            }
        }
    }

    private fun restoreEntitlement() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { _, list ->
            list.forEach { p ->
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val productId = p.products.firstOrNull() ?: return@forEach
                    verifyOnBackend(p.purchaseToken, productId) {
                        if (it) acknowledgeIfNeeded(p)
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
        billingClient.acknowledgePurchase(params) { }
    }

    // 🔧 CHANGED: передаём РЕАЛЬНЫЙ productId
    private fun verifyOnBackend(
        purchaseToken: String,
        productId: String,
        callback: (Boolean) -> Unit
    ) {
        bg.execute {
            try {
                val conn = (URL(VERIFY_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    authHeader?.let { setRequestProperty("Authorization", it) }
                }

                val body =
                    """{"productId":"$productId","purchaseToken":"$purchaseToken","packageName":"$packageName"}"""

                BufferedOutputStream(conn.outputStream).use {
                    it.write(body.toByteArray())
                }

                runOnUiThread { callback(conn.responseCode in 200..299) }
            } catch (_: Exception) {
                runOnUiThread { callback(false) }
            }
        }
    }

    // ================= WEBVIEW =================

    @Composable
    fun WebViewScreen(url: String) {
        AndroidView(factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(BillingJsBridge(), "billing")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val js = """
                          (function(){
                            var t = localStorage.getItem('accessToken') || localStorage.getItem('token');
                            if (t && !t.startsWith('Bearer ')) t = 'Bearer ' + t;
                            if (window.billing && t) window.billing.setAuth(t);
                          })();
                        """
                        view.evaluateJavascript(js, null)
                    }
                }

                loadUrl(url)
            }
        }, modifier = Modifier.fillMaxSize())
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ================= JS BRIDGE =================
    @Keep
    inner class BillingJsBridge {
        @JavascriptInterface
        fun buy(productId: String) {
            runOnUiThread { launchPurchase(productId) }
        }
        @JavascriptInterface
        fun setAuth(bearer: String) {
            authHeader = bearer
        }
    }
}
