package com.example.screenreadertest

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import android.text.style.BackgroundColorSpan


class MyAccessibilityService : AccessibilityService() {
    private var lastDetectedAmount: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckTime = 0L
    private var overlayView: View? = null
    private var centerPopupView: View? = null

    private var isConfirmed = false
    private var targetButtonNode: AccessibilityNodeInfo? = null
    private var ignoreUntil: Long = 0L
    private var backgroundOverlayView: View? = null

    private var isDeliver = false;
    private var lastNoClickTime = 0L

    private var currentPackageName: String? = null

    private lateinit var windowManager: WindowManager // WindowManager 미리 선언

    private var deliverCheckAttempts = 0        // 현재 결제 탐지 횟수
    private val maxDeliverCheckAttempts = 30     // MAX 결제 탐지 횟수

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return


        currentPackageName = packageName

        val targetApps = setOf(
            "com.example.screenreadertest",
            "com.coupang.mobile.eats",
            "com.sampleapp",
//            "com.fineapp.yogiyo"
        )

//        if (packageName !in targetApps) return

        // 주문 완료 감지
        if (isDeliver && event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
        ) {
            rootInActiveWindow?.let { node ->
                tryDetectOrderCompletion(node)
            }
        }

        // 결제하기 버튼 탐지
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                Log.d(
                    "AccessibilityService",
                    "Event type: ${event.eventType}, Package: $packageName"
                )
                if (packageName !in targetApps && !isAppInForeground(this))
                    removeOverlay()

                if (packageName in targetApps && packageName != "com.example.screenreadertest")
                    isConfirmed = false
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (packageName in targetApps) {
                    rootInActiveWindow?.let { node ->
                        handler.postDelayed({
                            Log.d("AccessibilityService", "checkbuttons")
                            checkButtons(node)
                        }, 100)
                    }
                }
            }
        }
    }



    override fun onInterrupt() {
        Log.d("AccessibilityService", "서비스 중단됨")
    }

    private fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses
        runningAppProcesses?.let { processes ->
            for (process in processes) {
                if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return process.processName == "com.example.screenreadertest"
                }
            }
        }
        return false
    }

    private fun checkButtons(node: AccessibilityNodeInfo) {
        Log.d("AccessibilityService", "🔍 현재 패키지 이름: $currentPackageName")
        val currentTime = System.currentTimeMillis()

        if (currentTime < ignoreUntil) {
            Log.d("AccessibilityService", "쿨다운 중으로 버튼 탐지 무시")
            return
        }
        if (isConfirmed) {
            Log.d("AccessibilityService", "쿨다운 종료 → isConfirmed = false")
            isConfirmed = false
        }

        if (currentTime - lastCheckTime < 200) return
        lastCheckTime = currentTime

        val stack = mutableListOf(node)
        var foundButton = false

        while (stack.isNotEmpty()) {
            val currentNode = stack.removeAt(stack.lastIndex)
            val className = currentNode.className?.toString()
            val nodeText = currentNode.text?.toString()
            val isButton = className?.contains("Button") == true
            val isTextView = className?.contains("TextView") == true

            if ((isButton || isTextView) && (nodeText?.contains("결제하기") == true || nodeText?.contains("Pay") == true)) {
                if (!currentNode.isVisibleToUser) continue

                val rect = Rect().apply { currentNode.getBoundsInScreen(this) }
                if (rect.top == rect.bottom) currentNode.parent?.getBoundsInScreen(rect)

                if (rect.top != rect.bottom) {
                    if (isConfirmed) {
                        currentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        targetButtonNode = currentNode // 클릭을 위해 저장
                        blockButtonWithOverlay(rect)
                    }
                    val amount = extractAmountFromText(nodeText)
                    if (amount > 0) {
                        Log.d("AccessibilityService", "🎯 버튼 텍스트에서 금액 추출: ${amount}원")
                        lastDetectedAmount = amount
                    }

                    blockButtonWithOverlay(rect)
                    foundButton = true
                    break // 첫 번째 버튼만 차단
                }
            }
            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { stack.add(it) }
            }
        }

        if (!foundButton) removeOverlay()
    }

    private fun blockButtonWithOverlay(rect: Rect) {
        removeOverlay()
        if (isConfirmed) return

        overlayView = View(this).apply {
//           setBackgroundColor(Color.argb(150, 255, 0, 0))
           setBackgroundColor(Color.argb(0, 255, 0, 0))
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d("AccessibilityService", "클릭 차단됨")
                    Log.d("AccessibilityService", "Checked!")
                    v.performClick()
//                    showPendingPopup()
                    showCenterPopup()
                }
                true
            }
        }

        Log.d("AccessibilityService", "blockButtonWithOverlay" )
        val (screenWidth, screenHeight) = getScreenSize()
        Log.d("AccessibilityService", "blockButtonWithOverlay, h: $screenHeight")
        Log.d("AccessibilityService", "Rect, $rect")

        val fullInsets = windowManager.currentWindowMetrics
            .windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())

        val layoutParams = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            val navSize = windowManager.currentWindowMetrics
                .windowInsets.getInsets(WindowInsets.Type.systemBars())
            this.x = rect.left
//            this.y = rect.top
            if (hasSoftNavigationBar())
                this.y = rect.top - fullInsets.top  // softkey 있는 환경
            else
                this.y = rect.top

            Log.d("AccessibilityService", "NavSize $navSize")
            Log.d("AccessibilityService", "SNav ${hasSoftNavigationBar()}")
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayView, layoutParams)
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val windowMetrics = windowManager.currentWindowMetrics
        val insets1 = windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val insets2 = windowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val width = windowMetrics.bounds.width() - insets2.left - insets2.right
        val height = windowMetrics.bounds.height() - insets2.top - insets2.bottom

        Log.d("AccessibilityService", "getInsetsIgn: ${insets1.bottom}, getInsets: ${insets2.bottom}")
        Log.d("AccessibilityService", "getInsetsIgn: $insets1, getInsets: $insets2")
        Log.d("AccessibilityService", "bounds: ${windowMetrics.bounds}")
        Log.d("AccessibilityService", "bounds: w:${windowMetrics.bounds.width()}, h:${windowMetrics.bounds.height()}")

        return width to height
    }

    private fun hasSoftNavigationBar(): Boolean {
        val metrics = windowManager.currentWindowMetrics
        val navInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        return navInsets.bottom > 80
    }

//    private fun getStatusBarHeight(context: Context): Int {
//        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
//        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
//    }
//
//    private fun getNavigationBarHeight(context: Context): Int {
//        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
//        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
//    }


    private fun showPendingPopup() {
        removeOverlay()

        overlayView = View(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0)) // 반투명 검정
            isClickable = true
            isFocusable = true

            setOnClickListener {
                showCenterPopup()  // <- 오버레이 클릭 시 팝업 띄우기
            }
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, overlayParams)
    }

    private fun showCenterPopup() {
        if (centerPopupView != null) return

        // ✅ 버튼 노드가 존재할 때 그 노드의 windowId → source 패키지를 우선적으로 사용
        val sourcePackageName = targetButtonNode?.packageName?.toString()
            ?: currentPackageName
            ?: rootInActiveWindow?.packageName?.toString()
            ?: ""

        Log.d("PopupDebug", "💥 showCenterPopup 호출됨, sourcePackage = $sourcePackageName")

        if (sourcePackageName.contains("coupang", ignoreCase = true)) {
            showCoupangEatsPopup()
        } else {
            showDefaultPopup()
        }
    }

    private fun getUsageSummaryViews(
        context: Context,
        highlightColor: Int,
        highlightAlpha: Int = 255
    ): Triple<TextView, TextView, TextView> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val yearMonth = "$year-$month"

        val (_, _, orderCount, orderAmount) = DeliveryEventManager.getMonthlyStats(context, yearMonth)
        val countText = "$orderCount"
        val amountText = "%,d".format(orderAmount)
        val summaryText = "배달 ${countText}회 ${amountText}원 사용"
        val alphaColor = Color.argb(highlightAlpha, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor))
        val summarySpannable = SpannableString(summaryText).apply {
            val boldTargets = listOf(countText, amountText)
            for (target in boldTargets) {
                val start = indexOf(target)
                if (start >= 0) {
                    setSpan(StyleSpan(Typeface.BOLD), start, start + target.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(alphaColor), start, start + target.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        val questionTextView = TextView(context).apply {
            text = "정말로 주문하시겠습니까?"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 6)
        }

        val subTextView = TextView(context).apply {
            text = "지난 1개월 동안 주문 내역"
            textSize = 14f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val summaryTextView = TextView(context).apply {
            text = summarySpannable
            textSize = 22f
            setTextColor(Color.parseColor("#444444"))
            isSingleLine = true
            maxLines = 1
            gravity = Gravity.CENTER
        }

        return Triple(questionTextView, subTextView, summaryTextView)
    }

    private fun showDefaultPopup() {
        // 🔹 배경 오버레이 (반투명)
        backgroundOverlayView = View(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
        }

        val bgParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(backgroundOverlayView, bgParams)

        val highlightColor = Color.parseColor("#33D0D0")
        val (questionTextView, subTextView, summaryTextView) = getUsageSummaryViews(this, highlightColor)
        questionTextView.setPadding(0, 0, 0, 60)
        subTextView.setPadding(0, 0, 0, 10)
        summaryTextView.setPadding(0, 0, 0, 40)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(questionTextView)
            addView(subTextView)
            addView(summaryTextView)
        }

        // 🔹 버튼 가로 배치
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 20, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val yesButton = Button(this).apply {
            text = "네"
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setTextColor(Color.BLACK)
            }
            elevation = 0f // 🔹 그림자 없애기
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            setPadding(12,4,12,4)
//            setOnClickListener {
//                isConfirmed = true
//                isDeliver = true
//                deliverCheckAttempts = 0
//                ignoreUntil = System.currentTimeMillis() + 10_000
//                removeOverlay()
//                removeCenterPopup()
//                overlayView?.isEnabled = false
//                targetButtonNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                targetButtonNode = null
//
//                handler.postDelayed({
//                    rootInActiveWindow?.let { tryDetectOrderCompletion(it) }
//                }, 1000)
//            }
            setOnClickListener {
                isConfirmed = true
                isDeliver = false
                ignoreUntil = System.currentTimeMillis() + 10_000
                removeOverlay()
                removeCenterPopup()
                overlayView?.isEnabled = false

                val amount = lastDetectedAmount
                DeliveryEventManager.appendEvent(applicationContext, amount, true)

                targetButtonNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetButtonNode = null
            }
        }

        val noButton = Button(this).apply {
            text = "아니요"
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f // ← 모서리 둥글게
                setColor(Color.parseColor("#0CEFD3"))
            }
            elevation = 0f
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastNoClickTime < 5 * 60 * 1000) {
                    Log.d("AccessibilityService", "쿨다운 중: 아니요 클릭 무시")
                    removeCenterPopup()
                    return@setOnClickListener
                }
                lastNoClickTime = now
                val amount = lastDetectedAmount
                DeliveryEventManager.appendEvent(applicationContext, amount, false)
                removeCenterPopup()
            }
        }

        buttonRow.addView(yesButton)
        buttonRow.addView(noButton)


        val (screenWidth, _) = getScreenSize()
        val desiredWidth = (screenWidth * 0.95).toInt()

        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.WHITE)
            }
            layoutParams = WindowManager.LayoutParams(
                desiredWidth,  // 고정값 대신 해상도 기준 비율
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            addView(layout)     // 텍스트 블록
            addView(buttonRow)  // 버튼 줄
        }

        val params = WindowManager.LayoutParams(
            750,  // ← 팝업 가로 크기 (픽셀) 직접 지정
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(popup, params)
        centerPopupView = popup
    }

    private fun showCoupangEatsPopup() {
            backgroundOverlayView = View(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0)) // 반투명 배경
        }
        val bgParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(backgroundOverlayView, bgParams)

        // ✅ 강조 색상
        val highlightColor = Color.parseColor("#00AEEF")
        val (questionTextView, subTextView, summaryTextView) = getUsageSummaryViews(this, highlightColor)
        questionTextView.setPadding(0, 0, 0, 60)
        subTextView.setPadding(0, 0, 0, 10)
        summaryTextView.setPadding(0, 0, 0, 40)

        // ✅ 상단 콘텐츠 재정리
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 30)
            addView(questionTextView)
            addView(subTextView)
            addView(summaryTextView)
        }

        val dividerView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }

        val yesButton = TextView(this).apply {
            text = "네"
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#666666")) // 회색 글자
            setBackgroundColor(Color.TRANSPARENT) // 배경은 흰색
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
//            setOnClickListener {
//                isConfirmed = true
//                isDeliver = true
//                deliverCheckAttempts = 0
//                ignoreUntil = System.currentTimeMillis() + 10_000
//                removeOverlay()
//                removeCenterPopup()
//                overlayView?.isEnabled = false
//                targetButtonNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                targetButtonNode = null
//
//                handler.postDelayed({
//                    rootInActiveWindow?.let { tryDetectOrderCompletion(it) }
//                }, 1000)
//            }
            setOnClickListener {
                isConfirmed = true
                isDeliver = false
                ignoreUntil = System.currentTimeMillis() + 10_000
                removeOverlay()
                removeCenterPopup()
                overlayView?.isEnabled = false

                val amount = lastDetectedAmount
                DeliveryEventManager.appendEvent(applicationContext, amount, true)

                targetButtonNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetButtonNode = null
            }
        }

        val noButton = TextView(this).apply {

            text = "아니요"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    0f, 0f,   // top-left
                    0f, 0f,
                    10f, 10f ,// top-right
                    0f, 0f,   // bottom-left
                )
                setColor(Color.parseColor("#00AEEF"))
            }

            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastNoClickTime < 5 * 60 * 1000) {
                    Log.d("AccessibilityService", "쿨다운 중: 아니요 클릭 무시")
                    removeCenterPopup()
                    return@setOnClickListener
                }
                lastNoClickTime = now
                val amount = lastDetectedAmount
                DeliveryEventManager.appendEvent(applicationContext, amount, false)
                removeCenterPopup()
            }
        }
        val buttonHeight = (45 * resources.displayMetrics.density).toInt()
// 버튼 영역
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                buttonHeight
            )
            weightSum = 2f
            addView(yesButton)
            addView(noButton)
        }

// 최종 팝업 뷰
        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 10f // 모든 모서리 둥글게
                setColor(Color.WHITE)
            }
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            addView(contentLayout)
            addView(dividerView)    // 경계선
            addView(buttonRow)      // 버튼
        }

        windowManager.addView(popup, popup.layoutParams)
        centerPopupView = popup
    }

    private fun removeCenterPopup() {
        centerPopupView?.let {
            windowManager.removeView(it)
            centerPopupView = null
        }
        backgroundOverlayView?.let {
            windowManager.removeView(it)
            backgroundOverlayView = null
        }
    }



    /**
     * 실제 배달 주문 확인을 위하여 '(정수)분' 키워드 확인
     */
    private fun extractAmountFromText(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val regex = Regex("""([\d,]+)\s*원""")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    }

    private fun checkOrderConfirmedByTime(node: AccessibilityNodeInfo): Boolean {
        val stack = mutableListOf(node)
        val regex = Regex("""\d{1,3}\s*분[^\d]*""")  // 1~3자리 숫자 + "분"

        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.lastIndex)
            val text = current.text?.toString() ?: continue

            if (regex.containsMatchIn(text)) {
                Log.d("AccessibilityService", "주문 완료 감지됨 (예상시간: $text)")
                return true
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
        }

        return false
    }

    private fun tryDetectOrderCompletion(node: AccessibilityNodeInfo) {
        if (!isDeliver) return

        val detected = checkOrderConfirmedByTime(node)
        if (detected) {
            // 주문 완료
            DeliveryEventManager.appendEvent(applicationContext, lastDetectedAmount, true)
            isDeliver = false
            return
        }

        if (deliverCheckAttempts < maxDeliverCheckAttempts) {
            deliverCheckAttempts++
            handler.postDelayed({
                rootInActiveWindow?.let { tryDetectOrderCompletion(it) }
            }, 2000)
        } else {
            Log.d("AccessibilityService", "❌ 감지 실패 – 타임아웃")
            isDeliver = false
        }
    }

}