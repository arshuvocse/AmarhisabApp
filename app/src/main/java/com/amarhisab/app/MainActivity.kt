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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.amarhisab.app.printer.BluetoothPrinterManager

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.amarhisab.app.printer.PrinterSettingsActivity

class MainActivity : AppCompatActivity(), WebAppInterface.BluetoothEnableRequester {

    lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: View
    private lateinit var fabPrinter: FloatingActionButton
    private lateinit var printerManager: BluetoothPrinterManager

    private val siteUrl by lazy { getString(R.string.site_url) }

    private var pendingBluetoothResult: ((Boolean) -> Unit)? = null

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == RESULT_OK
        pendingBluetoothResult?.invoke(granted)
        pendingBluetoothResult = null
    }

    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineView = findViewById(R.id.offlineView)
        fabPrinter = findViewById(R.id.fabPrinter)

        findViewById<Button>(R.id.retryButton).setOnClickListener { loadSite() }
        setupDraggableFab()

        printerManager = BluetoothPrinterManager(this)
        requestBluetoothPermissionsIfNeeded()

        // Enable Chrome DevTools inspection (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        setupWebView()
        swipeRefresh.isEnabled = false

        loadSite()
    }

    override fun onResume() {
        super.onResume()
        updateFabState()
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val missing = bluetoothPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT_PERMISSIONS)
        }
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE

                val overridePrintScript = """
                    (function() {
                        var cssLinks = [
                            { id: 'google-fonts-cdn', url: 'https://fonts.googleapis.com/css2?family=Hind+Siliguri:wght@300;400;500;600;700&family=Playfair+Display:wght@700;800;900&family=DM+Sans:wght@300;400;500;700&display=swap' },
                            { id: 'fontawesome-cdn', url: 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/5.9.0/css/all.min.css' },
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
                            style.innerHTML = '.fe, [class*="fe-"] { font-family: "feathericon", "Feather", "FontAwesome", sans-serif !important; display: inline-block; }';
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
                            var selector = 'i[class*="fe-"], i.fe, span[class*="fe-"], a[class*="fe-"], button[class*="fe-"]';
                            var elements = Array.prototype.slice.call(document.querySelectorAll(selector));

                            elements.forEach(function(el) {
                                if (el.tagName === 'SVG' || el.getAttribute('data-svg-replaced') === 'true') return;

                                var classes = (el.className || '').split(/\s+/);
                                var iconName = null;
                                for (var i = 0; i < classes.length; i++) {
                                    var c = classes[i];
                                    if (c.indexOf('fe-') === 0 && c.length > 3) {
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
                            var minArea = 2000; // ignore tiny icons/nav links that happen to match by class name
                            var best = null;
                            var bestArea = 0;

                            function consider(el) {
                                if (!el) return;
                                var w = Math.max(el.offsetWidth, el.scrollWidth);
                                var h = Math.max(el.offsetHeight, el.scrollHeight);
                                var area = w * h;
                                if (area > bestArea) { bestArea = area; best = el; }
                            }

                            // Prefer the largest <table> itself (NOT a wrapping .table-responsive/.card
                            // container) — those wrappers commonly use overflow-x:auto, which clips the
                            // rightmost column(s) out of the html2canvas screenshot if we capture the wrapper.
                            document.querySelectorAll('table').forEach(consider);

                            if (best && bestArea >= minArea) return best;

                            // No sizeable table found — fall back to class/id-based candidates, largest wins.
                            document.querySelectorAll('.printable, .receipt, #printableArea, #receipt, .invoice, #invoice, [class*="receipt"], [class*="invoice"], .card-body, .table-responsive').forEach(consider);

                            if (best && bestArea >= minArea) return best;
                            return document.body;
                        }

                        function captureAndPrint() {
                            if (!window.AndroidBridge) return;
                            var printable = pickPrintable();

                            function fallbackToText() {
                                window.AndroidBridge.printText((printable ? (printable.innerText || printable.textContent) : null) || document.body.innerText);
                            }

                            var hasSize = printable && printable.offsetWidth > 0 && printable.offsetHeight > 0;

                            if (window.html2canvas && printable && hasSize) {
                                var captured = false;
                                function doCapture() {
                                    if (captured) return;
                                    captured = true;
                                    window.html2canvas(printable, {
                                        scale: 2,
                                        useCORS: true,
                                        backgroundColor: '#ffffff',
                                        width: Math.max(printable.offsetWidth, printable.scrollWidth),
                                        height: Math.max(printable.offsetHeight, printable.scrollHeight),
                                        onclone: function(clonedDoc) {
                                            // Thermal paper is monochrome — a colored header background
                                            // just prints as a heavy black/gray bar. Force it white (with
                                            // black text) in the CAPTURED COPY only; the live page is
                                            // untouched. Match by the known header labels (not just <th>)
                                            // since the real markup may style plain <td> cells instead.
                                            function whiten(el) {
                                                el.style.setProperty('background', '#ffffff', 'important');
                                                el.style.setProperty('background-color', '#ffffff', 'important');
                                                el.style.setProperty('color', '#000000', 'important');
                                            }
                                            var headerWords = ['নাম', 'মূল্য', 'পরিমাণ', 'মোট'];
                                            clonedDoc.querySelectorAll('td, th').forEach(function(cell) {
                                                var txt = (cell.textContent || '').trim();
                                                if (headerWords.indexOf(txt) !== -1) {
                                                    var row = cell.closest('tr') || cell;
                                                    whiten(row);
                                                    row.querySelectorAll('td, th').forEach(whiten);
                                                }
                                            });
                                            clonedDoc.querySelectorAll('th').forEach(whiten);
                                        }
                                    }).then(function(canvas) {
                                        if (!canvas || canvas.width === 0 || canvas.height === 0) {
                                            console.error("html2canvas produced an empty canvas, falling back to text");
                                            fallbackToText();
                                            return;
                                        }
                                        var dataUrl = canvas.toDataURL('image/png');
                                        if (window.AndroidBridge.printBitmap) {
                                            window.AndroidBridge.printBitmap(dataUrl);
                                        } else {
                                            window.AndroidBridge.printBitmapBase64(dataUrl);
                                        }
                                    }).catch(function(e) {
                                        console.error("html2canvas error:", e);
                                        fallbackToText();
                                    });
                                }
                                // Wait for web fonts to finish loading (custom Bangla fonts can render
                                // late/blank otherwise, e.g. the item-table header row) — with a safety
                                // timeout so a stuck font promise never blocks printing indefinitely.
                                if (document.fonts && document.fonts.ready) {
                                    document.fonts.ready.then(doCapture).catch(doCapture);
                                }
                                setTimeout(doCapture, 400);
                            } else {
                                fallbackToText();
                            }
                        }

                        window.print = function() {
                            console.log("window.print() intercepted by Android WebView bridge");
                            captureAndPrint();
                        };

                        document.addEventListener('click', function(e) {
                            var btn = e.target.closest('button, a, .btn, [role="button"], input[type="button"], input[type="submit"]');
                            if (btn) {
                                var txt = (btn.innerText || btn.textContent || btn.value || '').toLowerCase();
                                var attr = ((btn.id || '') + ' ' + (btn.className || '') + ' ' + (btn.getAttribute('onclick') || '')).toLowerCase();
                                if (txt.indexOf('print') !== -1 || txt.indexOf('প্রিন্ট') !== -1 || attr.indexOf('print') !== -1) {
                                    console.log("Print element clicked: " + txt);
                                    e.preventDefault();
                                    captureAndPrint();
                                }
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
                            style.innerHTML = '.fe, [class*="fe-"] { font-family: "feathericon", "Feather", "FontAwesome", sans-serif !important; display: inline-block; }';
                            (document.head || document.documentElement).appendChild(style);
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(earlyFixScript, null)
            }

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

    private fun loadSite() {
        offlineView.visibility = View.GONE
        if (isNetworkAvailable()) {
            webView.loadUrl(siteUrl)
        } else {
            swipeRefresh.isRefreshing = false
            offlineView.visibility = View.VISIBLE
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * Shows the system "turn on Bluetooth" dialog (one tap for the user)
     * and reports back whether it ended up enabled.
     */
    @SuppressLint("MissingPermission")
    override fun requestEnableBluetooth(onResult: (Boolean) -> Unit) {
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

    /**
     * Updates the Floating Action Button icon and background color dynamically based on
     * printer connection state.
     */
    fun updateFabState() {
        val isConnected = printerManager.isConnected()
        val iconRes = if (isConnected) R.drawable.ic_bluetooth_connected else R.drawable.ic_bluetooth_disabled
        val bgColor = if (isConnected) Color.parseColor("#4CAF50") else Color.parseColor("#757575")

        val drawable = AppCompatResources.getDrawable(this, iconRes)
        fabPrinter.setImageDrawable(drawable)
        fabPrinter.backgroundTintList = ColorStateList.valueOf(bgColor)
        fabPrinter.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun handleFabClick() {
        if (!printerManager.isBluetoothEnabled()) {
            requestEnableBluetooth { enabled ->
                if (enabled) {
                    showPrinterActionOptions()
                } else {
                    Toast.makeText(this, "Bluetooth চালু করা প্রয়োজন", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            showPrinterActionOptions()
        }
    }

    private fun showPrinterActionOptions() {
        if (printerManager.isConnected()) {
            val printerName = printerManager.savedPrinterName() ?: printerManager.savedPrinterAddress() ?: "Unknown"
            AlertDialog.Builder(this)
                .setTitle("Printer Status")
                .setMessage("Connected to: $printerName")
                .setPositiveButton("Change Printer") { _, _ ->
                    startActivity(Intent(this, PrinterSettingsActivity::class.java))
                }
                .setNeutralButton("Disconnect") { _, _ ->
                    printerManager.disconnect()
                    Toast.makeText(this, "Printer disconnected", Toast.LENGTH_SHORT).show()
                    fabPrinter.postDelayed({ updateFabState() }, 300)
                }
                .setNegativeButton("Close", null)
                .show()
        } else {
            val paired = try {
                printerManager.pairedDevices()
            } catch (e: SecurityException) {
                emptyList()
            }

            if (paired.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Bluetooth Printer")
                    .setMessage("No paired Bluetooth printer found on device.")
                    .setPositiveButton("Printer Settings") { _, _ ->
                        startActivity(Intent(this, PrinterSettingsActivity::class.java))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } else {
                val deviceNames = paired.map {
                    try { it.name ?: it.address } catch (_: SecurityException) { it.address }
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("Select Printer to Connect")
                    .setItems(deviceNames) { _, which ->
                        val selectedDevice = paired[which]
                        Toast.makeText(this, "Connecting to ${deviceNames[which]}...", Toast.LENGTH_SHORT).show()
                        printerManager.connect(selectedDevice) { success, error ->
                            runOnUiThread {
                                if (success) {
                                    printerManager.saveDefaultPrinter(selectedDevice)
                                    Toast.makeText(this, "Connected: ${deviceNames[which]}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "Connection failed: $error", Toast.LENGTH_SHORT).show()
                                }
                                updateFabState()
                            }
                        }
                    }
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(this, PrinterSettingsActivity::class.java))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        fabPrinter.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0f
            private var initialY = 0f
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false
            private val clickThreshold = 10f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val parent = v.parent as? View ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = v.x
                        initialY = v.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
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
                        if (isClick) {
                            v.performClick()
                            handleFabClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    companion object {
        private const val REQUEST_BT_PERMISSIONS = 101
    }
}
