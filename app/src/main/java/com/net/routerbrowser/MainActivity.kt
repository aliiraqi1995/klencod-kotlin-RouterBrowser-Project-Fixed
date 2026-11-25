package com.net.routerbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: AutoCompleteTextView
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnTabs: Button

    // قائمة التبويبات (الصفحات المفتوحة)
    private val tabs = mutableListOf<WebView>()
    private var currentTabIndex = -1
    private var isDesktopMode = false

    // لدعم اختيار الملفات (رفع سوفتوير أو إعدادات للراوتر)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == RESULT_OK && result.data?.data != null) {
            arrayOf(result.data!!.data!!)
        } else {
            null
        }
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    // عناوين افتراضية جاهزة للاقتراح السريع
    private val defaultRouterUrls = listOf(
        "http://192.168.0.1",
        "http://192.168.1.1",
        "http://192.168.1.20",
        "http://192.168.0.254",
        "http://192.168.31.1",
        "http://192.168.8.1",
        "http://192.168.100.1",
        "http://tplinkwifi.net",
        "http://tendawifi.com",
        "http://miwifi.com"
    )

    private val historyList = mutableListOf<String>()
    private lateinit var urlAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadHistoryAndSuggestions()
        requestAllAppPermissions()

        val incomingUrl = intent?.dataString ?: "http://192.168.0.1"
        createNewTab(incomingUrl)
    }

    private fun initViews() {
        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)
        webContainer = findViewById(R.id.webContainer)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        btnTabs = findViewById(R.id.btnTabs)

        swipeRefresh.setOnRefreshListener {
            getCurrentWebView()?.reload()
        }

        findViewById<ImageButton>(R.id.btnGo).setOnClickListener {
            processInputUrl(urlInput.text.toString())
        }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                processInputUrl(urlInput.text.toString())
                true
            } else {
                false
            }
        }

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                urlInput.post { urlInput.selectAll() }
            }
        }

        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            getCurrentWebView()?.reload()
        }

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            showQuickRouterMenu()
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            getCurrentWebView()?.let { if (it.canGoBack()) it.goBack() }
        }

        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            getCurrentWebView()?.let { if (it.canGoForward()) it.goForward() }
        }

        btnTabs.setOnClickListener {
            showTabsDialog()
        }

        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }

        findViewById<ImageButton>(R.id.btnDesktop).setOnClickListener {
            toggleDesktopMode()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                if (isDesktopMode) {
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView?,
                    handler: HttpAuthHandler?,
                    host: String?,
                    realm: String?
                ) {
                    showHttpAuthDialog(handler, host ?: "Router")
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view == getCurrentWebView()) {
                        progressBar.visibility = View.VISIBLE
                        url?.let {
                            urlInput.setText(it)
                            saveToHistory(it)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == getCurrentWebView()) {
                        progressBar.visibility = View.GONE
                        swipeRefresh.isRefreshing = false
                        url?.let { urlInput.setText(it) }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == getCurrentWebView()) {
                        progressBar.progress = newProgress
                        if (newProgress == 100) progressBar.visibility = View.GONE
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    filePickerLauncher.launch(Intent.createChooser(intent, "اختر ملف"))
                    return true
                }
            }

            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                swipeRefresh.isEnabled = (scrollY == 0)
            }
        }

        tabs.add(webView)
        switchToTab(tabs.size - 1)
        processInputUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentTabIndex = index
        webContainer.removeAllViews()
        val activeWebView = tabs[index]
        webContainer.addView(activeWebView)
        urlInput.setText(activeWebView.url ?: "")
        updateTabsButtonCount()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            Toast.makeText(this, "لا يمكن إغلاق الصفحة الوحيدة", Toast.LENGTH_SHORT).show()
            return
        }
        val webViewToRemove = tabs[index]
        webViewToRemove.destroy()
        tabs.removeAt(index)

        val newIndex = if (index >= tabs.size) tabs.size - 1 else index
        switchToTab(newIndex)
    }

    private fun updateTabsButtonCount() {
        btnTabs.text = "صفحات [${tabs.size}]"
    }

    private fun getCurrentWebView(): WebView? {
        return if (currentTabIndex in tabs.indices) tabs[currentTabIndex] else null
    }

    private fun processInputUrl(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        hideKeyboard()
        urlInput.clearFocus()

        val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
        val domainPattern = Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$")

        val finalUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            ipv4Pattern.matches(input) -> "http://$input"
            domainPattern.matches(input) && !input.contains(" ") -> "http://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }

        urlInput.setText(finalUrl)
        getCurrentWebView()?.loadUrl(finalUrl)
    }

    private fun showQuickRouterMenu() {
        val items = arrayOf(
            "TP-Link / Tenda (192.168.0.1)",
            "Router الافتراضي (192.168.1.1)",
            "NanoStation / Ubiquiti (192.168.1.20)",
            "Xiaomi Router (192.168.31.1)",
            "Huawei Router (192.168.8.1)",
            "Huawei ONT (192.168.100.1)",
            "TP-Link Domain (tplinkwifi.net)",
            "Tenda Domain (tendawifi.com)"
        )
        val urls = arrayOf(
            "http://192.168.0.1",
            "http://192.168.1.1",
            "http://192.168.1.20",
            "http://192.168.31.1",
            "http://192.168.8.1",
            "http://192.168.100.1",
            "http://tplinkwifi.net",
            "http://tendawifi.com"
        )

        AlertDialog.Builder(this)
            .setTitle("اختصارات أجهزة الشبكة")
            .setItems(items) { _, which ->
                processInputUrl(urls[which])
            }
            .show()
    }

    private fun showTabsDialog() {
        val tabTitles = tabs.mapIndexed { idx, wv ->
            val title = wv.title?.takeIf { it.isNotBlank() } ?: wv.url ?: "صفحة جديدة"
            val marker = if (idx == currentTabIndex) "✔ " else ""
            "$marker${idx + 1}. $title"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة (${tabs.size})")
            .setItems(tabTitles) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton("+ صفحة جديدة") { _, _ ->
                createNewTab("http://192.168.0.1")
            }
            .setNeutralButton("إغلاق الصفحة الحالية") { _, _ ->
                closeTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showHistoryDialog() {
        if (historyList.isEmpty()) {
            Toast.makeText(this, "السجل فارغ حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val items = historyList.reversed().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("سجل العناوين السابقة")
            .setItems(items) { _, which ->
                processInputUrl(items[which])
            }
            .setNeutralButton("مسح السجل") { _, _ ->
                historyList.clear()
                saveHistoryToPrefs()
                updateSuggestionsAdapter()
                Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val webView = getCurrentWebView() ?: return
        webView.settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            null
        }
        webView.reload()
        val msg = if (isDesktopMode) "تم تفعيل عرض الكمبيوتر (Desktop)" else "تم تفعيل عرض الهاتف"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showHttpAuthDialog(handler: HttpAuthHandler?, host: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val userBox = EditText(this).apply { hint = "اسم المستخدم (admin / ubnt)" }
        val passBox = EditText(this).apply { hint = "كلمة المرور" }
        layout.addView(userBox)
        layout.addView(passBox)

        AlertDialog.Builder(this)
            .setTitle("تسجيل الدخول إلى $host")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                handler?.proceed(userBox.text.toString(), passBox.text.toString())
            }
            .setNegativeButton("إلغاء") { _, _ ->
                handler?.cancel()
            }
            .show()
    }

    private fun loadHistoryAndSuggestions() {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("history", "[]")
        val jsonArray = JSONArray(savedJson)
        historyList.clear()
        for (i in 0 until jsonArray.length()) {
            historyList.add(jsonArray.getString(i))
        }
        updateSuggestionsAdapter()
    }

    private fun saveToHistory(url: String) {
        if (url.isBlank() || url == "about:blank") return
        historyList.remove(url)
        historyList.add(url)
        if (historyList.size > 100) {
            historyList.removeAt(0)
        }
        saveHistoryToPrefs()
        updateSuggestionsAdapter()
    }

    private fun saveHistoryToPrefs() {
        val prefs = getSharedPreferences("RouterBrowserPrefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        historyList.forEach { jsonArray.put(it) }
        prefs.edit().putString("history", jsonArray.toString()).apply()
    }

    private fun updateSuggestionsAdapter() {
        val allSuggestions = (historyList.reversed() + defaultRouterUrls).distinct()
        urlAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, allSuggestions)
        urlInput.setAdapter(urlAdapter)
    }

    private fun requestAllAppPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val webView = getCurrentWebView()
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}