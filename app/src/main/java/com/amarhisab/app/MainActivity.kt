package com.amarhisab.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amarhisab.app.printer.BluetoothPrinterManager

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.amarhisab.app.printer.PrinterSettingsActivity

import android.widget.TextView
import android.widget.ImageView
import android.net.Network
import android.net.NetworkRequest
import com.amarhisab.app.utils.BluetoothPermissionHelper

class MainActivity : AppCompatActivity(), WebAppInterface.BluetoothEnableRequester {

    lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineView: View
    private lateinit var noInternetBanner: TextView
    private lateinit var fabPrinter: FloatingActionButton
    private lateinit var printerManager: BluetoothPrinterManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val siteUrl by lazy { getString(R.string.site_url) }

    private var pendingBluetoothResult: ((Boolean) -> Unit)? = null
    private var pendingBtPermissionAction: ((Boolean) -> Unit)? = null

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == RESULT_OK
        pendingBluetoothResult?.invoke(granted)
        pendingBluetoothResult = null
    }

    private val requestBtPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (!printerManager.isBluetoothEnabled()) {
                requestEnableBluetoothSystem { enabled ->
                    pendingBtPermissionAction?.invoke(enabled)
                    pendingBtPermissionAction = null
                }
            } else {
                pendingBtPermissionAction?.invoke(true)
                pendingBtPermissionAction = null
            }
        } else {
            com.amarhisab.app.utils.CustomToast.show(
                this,
                "প্রিন্টার ব্যবহার করতে ব্লুটুথ অনুমতি প্রয়োজন",
                isError = true
            )
            pendingBtPermissionAction?.invoke(false)
            pendingBtPermissionAction = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        offlineView = findViewById(R.id.offlineView)
        noInternetBanner = findViewById(R.id.noInternetBanner)
        fabPrinter = findViewById(R.id.fabPrinter)

        findViewById<Button>(R.id.retryButton).setOnClickListener { loadSite() }
        setupDraggableFab()
        restoreFabPosition()

        printerManager = BluetoothPrinterManager.getInstance(this)
        printerManager.onConnectionStateChanged = {
            runOnUiThread { updateFabState() }
        }

        // Enable Chrome DevTools inspection (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        setupWebView()

        registerNetworkCallback()
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val savedUrl = getSharedPreferences("amarhisab_prefs", MODE_PRIVATE).getString("last_url", null)
            loadSite(savedUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        updateFabState()
        restoreFabPosition()
        updateNetworkBannerState()
        if (!printerManager.isConnected() && printerManager.isBluetoothEnabled() && printerManager.hasSavedPrinter()) {
            printerManager.connectToSaved { _, _ ->
                runOnUiThread {
                    updateFabState()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateNetworkBannerState()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    updateNetworkBannerState()
                }
            }
        }
        networkCallback = callback

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateNetworkBannerState()
    }

    private fun updateNetworkBannerState() {
        val hasNet = isNetworkAvailable()
        noInternetBanner.visibility = if (hasNet) View.GONE else View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            loadWithOverviewMode = false
            useWideViewPort = false
            textZoom = 100
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        // Expose the native Kotlin API to the page's JavaScript as `window.AndroidBridge`
        webView.addJavascriptInterface(
            WebAppInterface(this, printerManager, this),
            "AndroidBridge"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                saveLastUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                saveLastUrl(url)
                progressBar.visibility = View.GONE

                val overridePrintScript = """
                    (function() {
                        var cssLinks = [
                            { id: 'icofont-cdn', url: 'https://amarhisab.com/public/frontend/css/icofont.min.css' },
                            { id: 'google-fonts-cdn', url: 'https://fonts.googleapis.com/css2?family=Hind+Siliguri:wght@300;400;500;600;700&family=Playfair+Display:wght@700;800;900&family=DM+Sans:wght@300;400;500;700&display=swap' },
                            { id: 'fontawesome-cdn', url: 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/5.9.0/css/all.min.css' },
                            { id: 'amarhisab-icons-css', url: 'https://amarhisab.com/public/dashboard/css/icons.css' },
                            { id: 'amarhisab-feather-css', url: 'https://amarhisab.com/public/dashboard/iconfonts/feather/feather.css' },
                            { id: 'amarhisab-fontawesome-css', url: 'https://amarhisab.com/public/dashboard/iconfonts/fontawesome-free/css/all.min.css' },
                            { id: 'feathericon-cdn', url: 'https://cdn.jsdelivr.net/npm/feathericon@1.0.2/build/css/feathericon.min.css' },
                            { id: 'feather-font-cdn', url: 'https://cdn.jsdelivr.net/npm/feather-font@1.0.0/src/css/feather.css' },
                            { id: 'feather-icons-cdn', url: 'https://cdn.jsdelivr.net/npm/feather-icons/dist/feather.css' }
                        ];
                        cssLinks.forEach(function(item) {
                            if (!document.getElementById(item.id)) {
                                var link = document.createElement('link');
                                link.id = item.id;
                                link.rel = 'stylesheet';
                                link.href = item.url;
                                document.head.appendChild(link);
                            }
                        });

                        if (!document.getElementById('feather-icon-fix-style')) {
                            var style = document.createElement('style');
                            style.id = 'feather-icon-fix-style';
                            style.innerHTML = '@font-face { font-family: "IcoFont"; src: url("https://amarhisab.com/public/frontend/fonts/icofont.woff2") format("woff2"), url("https://amarhisab.com/public/frontend/fonts/icofont.woff") format("woff"); } @font-face { font-family: "feather"; src: url("https://amarhisab.com/public/dashboard/iconfonts/feather/fonts/feather/feather-webfont.ttf") format("truetype"); font-weight: normal; font-style: normal; } @font-face { font-family: "LiAdorNoirrit"; src: url("https://amarhisab.com/public/fonts/LiAdorNoirritRegular.ttf") format("truetype"); } html body i, html body span.icofont, html body span.fe, html body i[class*="icofont-"], html body span[class*="icofont-"], html body i[class*="fe-"], html body span[class*="fe-"], html body .icofont, html body .fe, html body .fa, html body .fas, html body .far, html body .fab { font-family: "IcoFont", "icofont", "feather", "feathericon", "FontAwesome", "Font Awesome 5 Free", sans-serif !important; display: inline-block !important; }';
                            document.head.appendChild(style);
                        }

                        var builtInSvgs = {
                            'arrow-left': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>',
                            'arrow-right': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="5" y1="12" x2="19" y2="12"></line><polyline points="12 5 19 12 12 19"></polyline></svg>',
                            'log-in': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"></path><polyline points="10 17 15 12 10 7"></polyline><line x1="15" y1="12" x2="3" y2="12"></line></svg>',
                            'log-out': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>',
                            'send': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>',
                            'lock': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>',
                            'mail': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"></path><polyline points="22,6 12,13 2,6"></polyline></svg>',
                            'eye': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path><circle cx="12" cy="12" r="3"></circle></svg>',
                            'eye-off': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path><line x1="1" y1="1" x2="23" y2="23"></line></svg>',
                            'user': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>',
                            'home': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>',
                            'check': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>',
                            'x': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>',
                            'phone': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>',
                            'printer': '<svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 6 2 18 2 18 9"></polyline><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path><rect x="6" y="14" width="12" height="8"></rect></svg>'
                        };

                        function processFeatherIcons() {
                            var selector = 'i[class*="icofont-"], i[class*="fe-"], i.fe, i.icofont, span[class*="icofont-"], span[class*="fe-"], a[class*="icofont-"], a[class*="fe-"], button[class*="icofont-"], button[class*="fe-"]';
                            var elements = Array.prototype.slice.call(document.querySelectorAll(selector));

                            elements.forEach(function(el) {
                                if (el.tagName === 'SVG' || el.getAttribute('data-svg-replaced') === 'true') return;

                                var classes = (el.className || '').split(/\s+/);
                                var iconName = null;
                                for (var i = 0; i < classes.length; i++) {
                                    var c = classes[i];
                                    if (c.indexOf('icofont-') === 0 && c.length > 8) {
                                        iconName = c.substring(8);
                                        break;
                                    } else if (c.indexOf('fe-') === 0 && c.length > 3) {
                                        iconName = c.substring(3);
                                        break;
                                    }
                                }

                                if (!iconName && el.getAttribute('data-feather')) {
                                    iconName = el.getAttribute('data-feather');
                                }

                                var svgHtml = null;
                                if (iconName && window.feather && window.feather.icons && window.feather.icons[iconName]) {
                                    try {
                                        svgHtml = window.feather.icons[iconName].toSvg({
                                            'class': el.className + ' feather feather-' + iconName,
                                            'width': '1.2em',
                                            'height': '1.2em',
                                            'style': 'vertical-align: -0.15em; display: inline-block;'
                                        });
                                    } catch(e){}
                                }

                                if (!svgHtml && iconName && builtInSvgs[iconName]) {
                                    svgHtml = builtInSvgs[iconName];
                                }

                                if (svgHtml) {
                                    var temp = document.createElement('span');
                                    temp.innerHTML = svgHtml;
                                    var svgEl = temp.firstElementChild;
                                    if (svgEl) {
                                        svgEl.setAttribute('data-svg-replaced', 'true');
                                        svgEl.className.baseVal = (el.className || '') + ' feather feather-' + iconName;
                                        svgEl.style.verticalAlign = '-0.15em';
                                        svgEl.style.display = 'inline-block';
                                        if (el.parentNode) {
                                            el.parentNode.replaceChild(svgEl, el);
                                        }
                                    }
                                } else if (iconName && window.feather && window.feather.replace) {
                                    el.setAttribute('data-feather', iconName);
                                    try { window.feather.replace(); } catch(e){}
                                }
                            });
                        }

                        if (!document.getElementById('feather-svg-script')) {
                            var script = document.createElement('script');
                            script.id = 'feather-svg-script';
                            script.src = 'https://cdn.jsdelivr.net/npm/feather-icons/dist/feather.min.js';
                            script.onload = function() {
                                processFeatherIcons();
                            };
                            document.head.appendChild(script);
                        } else {
                            processFeatherIcons();
                        }

                        processFeatherIcons();
                        if (!window._featherInterval) {
                            window._featherInterval = setInterval(processFeatherIcons, 500);
                        }

                        if (window.MutationObserver && !window._featherObserved) {
                            window._featherObserved = true;
                            var observer = new MutationObserver(function() {
                                processFeatherIcons();
                            });
                            observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
                        }

                        if (!document.getElementById('html2canvas-script')) {
                            var script = document.createElement('script');
                            script.id = 'html2canvas-script';
                            script.src = 'https://cdn.jsdelivr.net/npm/html2canvas@1.4.1/dist/html2canvas.min.js';
                            document.head.appendChild(script);
                        }

                        if (window._androidBridgeInitialized) return;
                        window._androidBridgeInitialized = true;

                        function pickPrintable() {
                            var minArea = 2000;
                            var best = null;
                            var bestArea = 0;

                            function consider(el) {
                                if (!el) return;
                                var w = Math.max(el.offsetWidth, el.scrollWidth);
                                var h = Math.max(el.offsetHeight, el.scrollHeight);
                                var area = w * h;
                                if (area > bestArea) { bestArea = area; best = el; }
                            }

                            // 1. Explicit invoice table id (exact print target)
                            var invoiceTable = document.getElementById('printInvoiceTable');
                            if (invoiceTable) return invoiceTable;

                            // 2. Explicit receipt/invoice container elements (contains full header, table, totals, footer)
                            document.querySelectorAll('#printableArea, #printable, #receipt, #invoice, .printable, .receipt, .invoice, [class*="receipt"], [class*="invoice"], .pos-receipt, .print-area').forEach(consider);
                            if (best && bestArea >= minArea) return best;

                            // 3. Card or modal section wrapper
                            document.querySelectorAll('.modal-content, .card-body, .card, .main-content').forEach(consider);
                            if (best && bestArea >= minArea) return best;

                            // 4. Parent container of table if table exists
                            var tables = document.querySelectorAll('table');
                            tables.forEach(function(t) {
                                var parent = t.closest('.card, .card-body, .modal-content, .table-responsive, [class*="box"], [class*="container"]') || t.parentElement;
                                consider(parent || t);
                            });
                            if (best && bestArea >= minArea) return best;

                            // 5. Default to body
                            return document.body;
                        }

                        var _lastPrintTimestamp = 0;
                        function captureAndPrint() {
                            var now = Date.now();
                            if (now - _lastPrintTimestamp < 1500) {
                                console.log("Duplicate captureAndPrint call suppressed by debounce");
                                return;
                            }
                            _lastPrintTimestamp = now;

                            if (!window.AndroidBridge) return;
                            var printable = pickPrintable();

                            function doHtml2CanvasPrint(targetElement) {
                                var el = targetElement || printable;
                                if (window.html2canvas && el) {
                                    window.html2canvas(el, {
                                        scale: 3,
                                        useCORS: true,
                                        allowTaint: true,
                                        backgroundColor: '#ffffff'
                                    }).then(function(canvas) {
                                        var dataUrl = canvas.toDataURL('image/png');
                                        if (window.AndroidBridge.printBitmapBase64) {
                                            window.AndroidBridge.printBitmapBase64(dataUrl);
                                        } else if (window.AndroidBridge.printBitmap) {
                                            window.AndroidBridge.printBitmap(dataUrl);
                                        }
                                    }).catch(function(err) {
                                        console.error("html2canvas capture error:", err);
                                        if (el !== document.body) {
                                            doHtml2CanvasPrint(document.body);
                                        } else {
                                            fallbackPrintText();
                                        }
                                    });
                                } else {
                                    fallbackPrintText();
                                }
                            }

                            function fallbackPrintText() {
                                window.AndroidBridge.printText((printable ? (printable.innerText || printable.textContent) : null) || document.body.innerText);
                            }

                            if (!window.html2canvas) {
                                var script = document.createElement('script');
                                script.src = 'https://cdn.jsdelivr.net/npm/html2canvas@1.4.1/dist/html2canvas.min.js';
                                script.onload = function() { doHtml2CanvasPrint(); };
                                script.onerror = function() { fallbackPrintText(); };
                                document.head.appendChild(script);
                            } else {
                                doHtml2CanvasPrint();
                            }
                        }

                        window.print = function() {
                            console.log("window.print() intercepted by Android WebView bridge");
                            captureAndPrint();
                        };

                        document.addEventListener('click', function(e) {
                            var el = e.target;
                            var btn = el ? el.closest('#printPosButton') : null;

                            if (btn) {
                                console.log("Print button captured: printPosButton");
                                e.preventDefault();
                                e.stopPropagation();
                                captureAndPrint();
                            }
                        }, true);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(overridePrintScript, null)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val earlyFixScript = """
                    (function() {
                        if (!document.getElementById('feather-icon-fix-style')) {
                            var style = document.createElement('style');
                            style.id = 'feather-icon-fix-style';
                            style.innerHTML = '@font-face { font-family: "IcoFont"; src: url("https://amarhisab.com/public/frontend/fonts/icofont.woff2") format("woff2"), url("https://amarhisab.com/public/frontend/fonts/icofont.woff") format("woff"); } @font-face { font-family: "feather"; src: url("https://amarhisab.com/public/dashboard/iconfonts/feather/fonts/feather/feather-webfont.ttf") format("truetype"); font-weight: normal; font-style: normal; } @font-face { font-family: "LiAdorNoirrit"; src: url("https://amarhisab.com/public/fonts/LiAdorNoirritRegular.ttf") format("truetype"); } html body i, html body span.icofont, html body span.fe, html body i[class*="icofont-"], html body span[class*="icofont-"], html body i[class*="fe-"], html body span[class*="fe-"], html body .icofont, html body .fe, html body .fa, html body .fas, html body .far, html body .fab { font-family: "IcoFont", "icofont", "feather", "feathericon", "FontAwesome", "Font Awesome 5 Free", sans-serif !important; display: inline-block !important; }';
                            (document.head || document.documentElement).appendChild(style);
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(earlyFixScript, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleCustomIntentUrl(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleCustomIntentUrl(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    if (!isNetworkAvailable()) {
                        offlineView.visibility = View.VISIBLE
                    } else {
                        com.amarhisab.app.utils.CustomToast.show(
                            this@MainActivity,
                            "সার্ভারে সংযোগে সমস্যা হচ্ছে। পেজ রিফ্রেশ করুন।",
                            isError = true
                        )
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (!isNetworkAvailable()) {
                    offlineView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
    }

    private fun handleCustomIntentUrl(url: String): Boolean {
        if (url.startsWith("whatsapp://") ||
            url.contains("api.whatsapp.com") ||
            url.contains("wa.me") ||
            url.startsWith("intent://") ||
            url.startsWith("tel:") ||
            url.startsWith("mailto:")
        ) {
            try {
                val intent = if (url.startsWith("intent://")) {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return true
                } else {
                    val webUrl = if (url.startsWith("whatsapp://send")) {
                        url.replace("whatsapp://send", "https://api.whatsapp.com/send")
                    } else url
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(webUrl)))
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun saveLastUrl(url: String?) {
        if (!url.isNullOrEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
            getSharedPreferences("amarhisab_prefs", MODE_PRIVATE)
                .edit()
                .putString("last_url", url)
                .apply()
        }
    }

    private fun loadSite(targetUrl: String? = null) {
        offlineView.visibility = View.GONE
        updateNetworkBannerState()
        val urlToLoad = targetUrl
            ?: webView.url
            ?: getSharedPreferences("amarhisab_prefs", MODE_PRIVATE).getString("last_url", null)
            ?: siteUrl

        if (isNetworkAvailable()) {
            webView.loadUrl(urlToLoad)
        } else {
            offlineView.visibility = View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            showExitConfirmDialog()
        }
    }

    private fun showExitConfirmDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exit_confirm, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<Button>(R.id.btnExitConfirm).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialogView.findViewById<Button>(R.id.btnExitCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Ensures Bluetooth permission is granted and Bluetooth is enabled, showing a reassuring
     * trust rationale dialog BEFORE requesting system permission or turning on Bluetooth.
     */
    fun ensureBluetoothReadyWithRationale(onResult: (Boolean) -> Unit) {
        val hasPermissions = BluetoothPermissionHelper.hasBluetoothPermissions(this)
        val isEnabled = printerManager.isBluetoothEnabled()

        if (hasPermissions && isEnabled) {
            onResult(true)
            return
        }

        BluetoothPermissionHelper.showRationaleDialog(
            context = this,
            onAllow = {
                if (!hasPermissions) {
                    pendingBtPermissionAction = onResult
                    requestBtPermissionsLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
                } else if (!isEnabled) {
                    requestEnableBluetoothSystem(onResult)
                } else {
                    onResult(true)
                }
            },
            onCancel = {
                onResult(false)
            }
        )
    }

    /**
     * Shows the system "turn on Bluetooth" dialog (one tap for the user)
     * and reports back whether it ended up enabled.
     */
    @SuppressLint("MissingPermission")
    private fun requestEnableBluetoothSystem(onResult: (Boolean) -> Unit) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            onResult(false)
            return
        }
        if (adapter.isEnabled) {
            onResult(true)
            return
        }
        pendingBluetoothResult = onResult
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    override fun requestEnableBluetooth(onResult: (Boolean) -> Unit) {
        runOnUiThread {
            ensureBluetoothReadyWithRationale(onResult)
        }
    }

    /**
     * Updates the Floating Action Button icon and background color dynamically based on
     * printer connection state.
     */
    fun updateFabState() {
        val isConnected = printerManager.isConnected()
        val hasSaved = printerManager.hasSavedPrinter()

        val iconRes = if (isConnected) R.drawable.ic_bluetooth_connected else R.drawable.ic_bluetooth_disabled
        val bgColor = when {
            isConnected -> Color.parseColor("#4CAF50") // Green when connected
            hasSaved -> Color.parseColor("#FF9800")    // Amber/Orange when printer is saved but disconnected
            else -> Color.parseColor("#757575")        // Grey when no printer is saved
        }

        val drawable = AppCompatResources.getDrawable(this, iconRes)
        fabPrinter.setImageDrawable(drawable)
        fabPrinter.backgroundTintList = ColorStateList.valueOf(bgColor)
        androidx.core.view.ViewCompat.setBackgroundTintList(fabPrinter, ColorStateList.valueOf(bgColor))
        fabPrinter.imageTintList = ColorStateList.valueOf(Color.WHITE)
        fabPrinter.invalidate()
        fabPrinter.requestLayout()
    }

    private fun handleFabClick() {
        ensureBluetoothReadyWithRationale { ready ->
            if (ready) {
                showPrinterActionOptions()
            } else {
                com.amarhisab.app.utils.CustomToast.show(this, "ব্লুটুথ অনুমতি ও অন থাকা প্রয়োজন", isError = true)
            }
        }
    }

    fun showPrinterActionOptions() {
        val isConnected = printerManager.isConnected()
        val hasSaved = printerManager.hasSavedPrinter()

        if (!hasSaved && !isConnected) {
            val paired = try { printerManager.pairedDevices() } catch (e: SecurityException) { emptyList() }
            if (paired.isEmpty()) {
                startActivity(Intent(this, PrinterSettingsActivity::class.java))
                return
            }
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_printer_status, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val dialogIcon = dialogView.findViewById<ImageView>(R.id.dialogIcon)
        val dialogIconContainer = dialogView.findViewById<View>(R.id.dialogIconContainer)
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val dialogStatusBadge = dialogView.findViewById<TextView>(R.id.dialogStatusBadge)
        val dialogPrinterName = dialogView.findViewById<TextView>(R.id.dialogPrinterName)
        val dialogPrinterAddress = dialogView.findViewById<TextView>(R.id.dialogPrinterAddress)
        val btnPrimaryAction = dialogView.findViewById<Button>(R.id.btnPrimaryAction)
        val btnSettingsAction = dialogView.findViewById<Button>(R.id.btnSettingsAction)
        val btnDangerAction = dialogView.findViewById<Button>(R.id.btnDangerAction)
        val btnCloseDialog = dialogView.findViewById<Button>(R.id.btnCloseDialog)

        val savedName = printerManager.savedPrinterName() ?: "কোনো প্রিন্টার সিলেক্ট করা নেই"
        val savedAddress = printerManager.savedPrinterAddress() ?: ""

        dialogPrinterName.text = savedName
        dialogPrinterAddress.text = if (savedAddress.isNotEmpty()) savedAddress else "প্রিন্টার সেটিংস থেকে বেছে নিন"

        if (isConnected) {
            dialogTitle.text = "প্রিন্টার কানেক্টেড"
            dialogStatusBadge.text = "সংযুক্ত (Connected)"
            dialogStatusBadge.setBackgroundResource(R.drawable.bg_glossy_badge_green)
            dialogStatusBadge.setTextColor(Color.parseColor("#10B981"))
            dialogIconContainer.setBackgroundResource(R.drawable.bg_glossy_badge_green)
            dialogIcon.setImageResource(R.drawable.ic_bluetooth_connected)
            dialogIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))

            btnPrimaryAction.visibility = View.GONE
            btnDangerAction.visibility = View.VISIBLE

            btnDangerAction.setOnClickListener {
                dialog.dismiss()
                printerManager.disconnect()
                com.amarhisab.app.utils.CustomToast.show(this, "প্রিন্টার ডিসকানেক্ট করা হয়েছে")
                fabPrinter.postDelayed({ updateFabState() }, 300)
            }
        } else {
            dialogTitle.text = "প্রিন্টার স্ট্যাটাস"
            dialogStatusBadge.text = if (hasSaved) "ডিসকানেক্টেড (Saved)" else "কোনো সেভ করা প্রিন্টার নেই"
            dialogStatusBadge.setBackgroundResource(R.drawable.bg_glossy_badge_orange)
            dialogStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            dialogIconContainer.setBackgroundResource(R.drawable.bg_glossy_badge_orange)
            dialogIcon.setImageResource(R.drawable.ic_bluetooth_disabled)
            dialogIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))

            if (hasSaved) {
                btnPrimaryAction.visibility = View.VISIBLE
                btnPrimaryAction.text = "কানেক্ট করুন"
                btnPrimaryAction.setOnClickListener {
                    dialog.dismiss()
                    com.amarhisab.app.utils.CustomToast.show(this, "$savedName এর সাথে কানেক্ট করা হচ্ছে...")
                    printerManager.connectToSaved { success, error ->
                        runOnUiThread {
                            if (success) {
                                com.amarhisab.app.utils.CustomToast.show(this, "সংযুক্ত হয়েছে: $savedName", isSuccess = true)
                            } else {
                                com.amarhisab.app.utils.CustomToast.show(this, "কানেকশন ব্যর্থ: ${error ?: "অজানা সমস্যা"}", isError = true)
                            }
                            updateFabState()
                        }
                    }
                }
            } else {
                btnPrimaryAction.visibility = View.GONE
            }
            btnDangerAction.visibility = View.GONE
        }

        btnSettingsAction.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, PrinterSettingsActivity::class.java))
        }

        btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        fabPrinter.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0f
            private var initialY = 0f
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false
            private var downTime = 0L
            private val clickThreshold = 10f
            private val clickDurationThreshold = 300L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val parent = v.parent as? View ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = v.x
                        initialY = v.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        downTime = System.currentTimeMillis()
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dX = event.rawX - initialTouchX
                        val dY = event.rawY - initialTouchY

                        if (Math.abs(dX) > clickThreshold || Math.abs(dY) > clickThreshold) {
                            isClick = false
                        }

                        var newX = initialX + dX
                        var newY = initialY + dY

                        val maxX = (parent.width - v.width).toFloat()
                        val maxY = (parent.height - v.height).toFloat()

                        newX = newX.coerceIn(0f, maxX)
                        newY = newY.coerceIn(0f, maxY)

                        v.x = newX
                        v.y = newY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val heldTooLong = System.currentTimeMillis() - downTime > clickDurationThreshold
                        if (isClick && !heldTooLong) {
                            v.performClick()
                            handleFabClick()
                        } else {
                            saveFabPosition(v.x, v.y)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun saveFabPosition(x: Float, y: Float) {
        getSharedPreferences("amarhisab_prefs", MODE_PRIVATE)
            .edit()
            .putFloat("fab_position_x", x)
            .putFloat("fab_position_y", y)
            .putBoolean("fab_has_saved_pos", true)
            .apply()
    }

    private fun restoreFabPosition() {
        val prefs = getSharedPreferences("amarhisab_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("fab_has_saved_pos", false)) return

        val savedX = prefs.getFloat("fab_position_x", -1f)
        val savedY = prefs.getFloat("fab_position_y", -1f)

        if (savedX >= 0f && savedY >= 0f) {
            fabPrinter.post {
                val parent = fabPrinter.parent as? View ?: return@post
                val maxX = (parent.width - fabPrinter.width).toFloat()
                val maxY = (parent.height - fabPrinter.height).toFloat()

                if (maxX > 0 && maxY > 0) {
                    fabPrinter.x = savedX.coerceIn(0f, maxX)
                    fabPrinter.y = savedY.coerceIn(0f, maxY)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_BT_PERMISSIONS = 101
    }
}
