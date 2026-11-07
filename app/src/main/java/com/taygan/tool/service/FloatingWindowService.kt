package com.taygan.tool.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taygan.tool.MainActivity
import com.taygan.tool.R
import com.taygan.tool.adapter.VoucherAdapter
import com.taygan.tool.api.SaveApiResult
import com.taygan.tool.api.ShopeeApiService
import com.taygan.tool.api.ShopeeSessionManager
import com.taygan.tool.model.BadgeType
import com.taygan.tool.model.VoucherItem
import com.taygan.tool.utils.PrefManager
import com.taygan.tool.utils.TimeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingWindowService : Service() {

    private val tag = "FloatingWindowService"
    private lateinit var windowManager: WindowManager
    private var expandedView: View? = null
    private var minimizedBubbleView: View? = null

    private lateinit var prefManager: PrefManager
    private lateinit var sessionManager: ShopeeSessionManager
    private lateinit var apiService: ShopeeApiService

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var clockJob: Job? = null
    private var huntCountdownJob: Job? = null

    private var isMinimized = false
    private var isHuntingArmed = false

    private lateinit var voucherAdapter: VoucherAdapter
    private var currentVouchers: MutableList<VoucherItem> = mutableListOf()

    private var expandedParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    companion object {
        private const val CHANNEL_ID = "tay_gan_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        var isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        prefManager = PrefManager(this)
        sessionManager = ShopeeSessionManager.getInstance(this)
        apiService = ShopeeApiService(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Safe Foreground notification start (Bọc an toàn chống crash trên Android 14)
        startForegroundSafely()

        // Khởi tạo Views
        initViews()

        // Bắt đầu đồng hồ mili-giây
        startRealtimeClock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (expandedView == null) {
            initViews()
        } else if (expandedView?.isAttachedToWindow == false && !isMinimized) {
            try {
                expandedParams?.let { windowManager.addView(expandedView, it) }
            } catch (e: Throwable) {
                Log.e(tag, "Error re-attaching window: ${e.message}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        clockJob?.cancel()
        huntCountdownJob?.cancel()
        removeViews()
    }

    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Tay Gân Tool Overlay",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Bảng điều khiển nổi săn sale Shopee"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tay Gân Tool đang chạy")
                .setContentText("Bảng điều khiển nổi sẵn sàng")
                .setSmallIcon(R.drawable.ic_launcher_app)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e(tag, "startForegroundSafely warning (non-fatal): ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initViews() {
        // Kiểm tra quyền Cửa sổ nổi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa cấp quyền Hiển thị trên ứng dụng khác!", Toast.LENGTH_LONG).show()
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Layout params cho Cửa sổ mở rộng
        expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 120
        }

        // Layout params cho Thanh nổi thu nhỏ (Chuẩn như video tool gốc)
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = -80
        }

        try {
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_TayGanTool)
            val inflater = LayoutInflater.from(themedContext)
            expandedView = inflater.inflate(R.layout.layout_floating_window, null)
            minimizedBubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

            setupExpandedViewLogic()
            setupBubbleViewLogic()

            // Thêm View vào WindowManager
            if (expandedView?.isAttachedToWindow == false) {
                windowManager.addView(expandedView, expandedParams)
            }
        } catch (e: Throwable) {
            Log.e(tag, "Fatal error adding floating view: ${e.message}", e)
            Toast.makeText(this, "Lỗi mở cửa sổ nổi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExpandedViewLogic() {
        val root = expandedView ?: return
        val params = expandedParams ?: return

        val headerBar = root.findViewById<View>(R.id.headerBar)
        headerBar?.setOnTouchListener { _, event ->
            handleTouchDrag(event, params, expandedView)
        }

        root.findViewById<View>(R.id.btnCloseWindow)?.setOnClickListener {
            stopSelf()
        }

        root.findViewById<View>(R.id.btnMinimize)?.setOnClickListener {
            minimizeToBubble()
        }

        // Tabs
        val tabHuntCode = root.findViewById<TextView>(R.id.tabHuntCode)
        val tabDealSvip = root.findViewById<TextView>(R.id.tabDealSvip)
        tabHuntCode?.setOnClickListener {
            tabHuntCode.setBackgroundResource(R.drawable.bg_tab_active)
            tabHuntCode.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            tabDealSvip?.setBackgroundResource(R.drawable.bg_tab_inactive)
            tabDealSvip?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            prefManager.activeTab = 0
        }
        tabDealSvip?.setOnClickListener {
            tabDealSvip.setBackgroundResource(R.drawable.bg_tab_active)
            tabDealSvip.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            tabHuntCode?.setBackgroundResource(R.drawable.bg_tab_inactive)
            tabHuntCode?.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            prefManager.activeTab = 1
            Toast.makeText(this, "Đã bật chế độ Săn Deal SVIP (API)", Toast.LENGTH_SHORT).show()
        }

        // Selected Voucher Preview Dropdown
        val previewRoot = root.findViewById<View>(R.id.llSelectedVoucherPreview)
        val tvPreviewBadge = root.findViewById<TextView>(R.id.tvPreviewBadge)
        val tvPreviewTitle = root.findViewById<TextView>(R.id.tvPreviewTitle)
        val ivArrow = root.findViewById<ImageView>(R.id.ivExpandCollapseArrow)
        val rvVouchers = root.findViewById<RecyclerView>(R.id.rvVouchers)

        currentVouchers = prefManager.getVouchers()
        root.findViewById<TextView>(R.id.tvVoucherCountBadge)?.text = "${currentVouchers.size} mã"

        voucherAdapter = VoucherAdapter(currentVouchers) { selectedItem ->
            tvPreviewTitle?.text = selectedItem.code
            tvPreviewBadge?.text = if (selectedItem.isApiClaimable) "API" else if (selectedItem.badgeType == BadgeType.VIP) "VIP" else "SALE"
            prefManager.saveVouchers(currentVouchers)
        }

        val initialSelected = voucherAdapter.getSelectedItem()
        if (initialSelected != null) {
            tvPreviewTitle?.text = initialSelected.code
            tvPreviewBadge?.text = if (initialSelected.isApiClaimable) "API" else "VIP"
        }

        rvVouchers?.layoutManager = LinearLayoutManager(this)
        rvVouchers?.adapter = voucherAdapter

        previewRoot?.setOnClickListener {
            if (rvVouchers?.visibility == View.VISIBLE) {
                rvVouchers.visibility = View.GONE
                ivArrow?.setImageResource(R.drawable.ic_arrow_down)
            } else {
                rvVouchers?.visibility = View.VISIBLE
                ivArrow?.setImageResource(R.drawable.ic_arrow_up)
            }
        }

        // Switches
        val switchHuntByPercent = root.findViewById<SwitchCompat>(R.id.switchHuntByPercent)
        val switchAutoOrder = root.findViewById<SwitchCompat>(R.id.switchAutoOrder)
        val switchHuntByTime = root.findViewById<SwitchCompat>(R.id.switchHuntByTime)
        val timeContainer = root.findViewById<View>(R.id.llTimeInputsContainer)

        switchHuntByPercent?.isChecked = prefManager.huntByPercent
        switchAutoOrder?.isChecked = prefManager.autoOrder
        switchHuntByTime?.isChecked = prefManager.huntByTime

        switchHuntByPercent?.setOnCheckedChangeListener { _, isChecked ->
            prefManager.huntByPercent = isChecked
        }
        switchAutoOrder?.setOnCheckedChangeListener { _, isChecked ->
            prefManager.autoOrder = isChecked
        }
        switchHuntByTime?.setOnCheckedChangeListener { _, isChecked ->
            prefManager.huntByTime = isChecked
            timeContainer?.alpha = if (isChecked) 1.0f else 0.4f
        }

        // Time inputs
        val etHour = root.findViewById<EditText>(R.id.etTargetHour)
        val etMinute = root.findViewById<EditText>(R.id.etTargetMinute)
        val etSecond = root.findViewById<EditText>(R.id.etTargetSecond)

        etHour?.setText(String.format("%02d", prefManager.targetHour))
        etMinute?.setText(String.format("%02d", prefManager.targetMinute))
        etSecond?.setText(String.format("%02d", prefManager.targetSecond))

        // Focus handler để mở bàn phím khi bấm vào ô nhập giờ
        setupKeyboardFocus(etHour, params)
        setupKeyboardFocus(etMinute, params)
        setupKeyboardFocus(etSecond, params)

        // Action Buttons
        val btnStart = root.findViewById<View>(R.id.btnStart)
        val tvStartText = root.findViewById<TextView>(R.id.tvStartText)
        val btnStop = root.findViewById<View>(R.id.btnStop)

        btnStart?.setOnClickListener {
            val h = etHour?.text?.toString()?.toIntOrNull() ?: prefManager.targetHour
            val m = etMinute?.text?.toString()?.toIntOrNull() ?: prefManager.targetMinute
            val s = etSecond?.text?.toString()?.toIntOrNull() ?: prefManager.targetSecond
            prefManager.targetHour = h
            prefManager.targetMinute = m
            prefManager.targetSecond = s

            tvStartText?.let { startHunting(it) }
        }

        btnStop?.setOnClickListener {
            tvStartText?.let { stopHunting(it) }
        }

        root.findViewById<View>(R.id.llServiceStatus)?.setOnClickListener {
            if (!TayGanAccessibilityService.isConnected()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                Toast.makeText(this, "Vui lòng chạm vào 'Tay Gân Tool' và BẬT công tắc!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupKeyboardFocus(editText: EditText?, params: WindowManager.LayoutParams) {
        editText?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            try {
                if (expandedView?.isAttachedToWindow == true) {
                    windowManager.updateViewLayout(expandedView, params)
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleViewLogic() {
        val bubble = minimizedBubbleView ?: return
        val params = bubbleParams ?: return

        bubble.setOnTouchListener { _, event ->
            handleTouchDrag(event, params, minimizedBubbleView)
        }

        bubble.setOnClickListener {
            expandToWindow()
        }

        bubble.findViewById<View>(R.id.btnBubbleExpand)?.setOnClickListener {
            expandToWindow()
        }
    }

    private fun handleTouchDrag(event: MotionEvent, params: WindowManager.LayoutParams, targetView: View?): Boolean {
        if (targetView == null) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX - (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                try {
                    if (targetView.isAttachedToWindow) {
                        windowManager.updateViewLayout(targetView, params)
                    }
                } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP -> {
                val diffX = Math.abs(event.rawX - initialTouchX)
                val diffY = Math.abs(event.rawY - initialTouchY)
                if (diffX < 12 && diffY < 12) {
                    targetView.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun minimizeToBubble() {
        if (isMinimized) return
        isMinimized = true
        try {
            if (expandedView?.isAttachedToWindow == true) {
                windowManager.removeView(expandedView)
            }
            if (minimizedBubbleView?.isAttachedToWindow == false) {
                bubbleParams?.let { windowManager.addView(minimizedBubbleView, it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error minimizing: ${e.message}")
        }
    }

    private fun expandToWindow() {
        if (!isMinimized) return
        isMinimized = false
        try {
            if (minimizedBubbleView?.isAttachedToWindow == true) {
                windowManager.removeView(minimizedBubbleView)
            }
            if (expandedView?.isAttachedToWindow == false) {
                expandedParams?.let { windowManager.addView(expandedView, it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error expanding: ${e.message}")
        }
    }

    private fun startHunting(tvStartText: TextView) {
        if (!TayGanAccessibilityService.isConnected()) {
            Toast.makeText(this, "Lưu ý: Dịch vụ Trợ năng chưa bật!", Toast.LENGTH_LONG).show()
        }

        val selectedVoucher = voucherAdapter.getSelectedItem() ?: return
        val autoOrder = prefManager.autoOrder
        val huntByTime = prefManager.huntByTime

        isHuntingArmed = true
        tvStartText.text = "Đang chạy..."

        val triggerCombo = {
            serviceScope.launch {
                if (selectedVoucher.isApiClaimable && sessionManager.isLoggedIn) {
                    tvStartText.text = "Lưu API..."
                    val apiResult = apiService.saveVoucherDirect(selectedVoucher)
                    when (apiResult) {
                        is SaveApiResult.Success -> {
                            Toast.makeText(this@FloatingWindowService, "API: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                        is SaveApiResult.Retry -> {
                            Toast.makeText(this@FloatingWindowService, "API: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                        is SaveApiResult.Failed -> {
                            Toast.makeText(this@FloatingWindowService, "API: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                TayGanAccessibilityService.instance?.triggerHunting(selectedVoucher.code, autoOrder)
                tvStartText.text = "Đã bắn mã!"
                minimizeToBubble()
            }
        }

        if (!huntByTime) {
            triggerCombo()
            Toast.makeText(this, "Đã kích hoạt săn tức thì!", Toast.LENGTH_SHORT).show()
            minimizeToBubble()
            return
        }

        val remainingMillis = TimeHelper.getRemainingMillis(
            prefManager.targetHour,
            prefManager.targetMinute,
            prefManager.targetSecond
        )

        Toast.makeText(
            this,
            "Đã hẹn giờ săn: ${prefManager.targetHour}:${prefManager.targetMinute}:${prefManager.targetSecond}",
            Toast.LENGTH_SHORT
        ).show()

        huntCountdownJob?.cancel()
        huntCountdownJob = serviceScope.launch {
            if (remainingMillis > 500) {
                delay(remainingMillis - 500)
            }
            while (isActive && isHuntingArmed) {
                val rem = TimeHelper.getRemainingMillis(
                    prefManager.targetHour,
                    prefManager.targetMinute,
                    prefManager.targetSecond
                )
                if (rem <= 50) {
                    triggerCombo()
                    break
                }
                delay(10)
            }
        }
    }

    private fun stopHunting(tvStartText: TextView) {
        isHuntingArmed = false
        huntCountdownJob?.cancel()
        TayGanAccessibilityService.instance?.stopHunting()
        tvStartText.text = "Bắt đầu"
        Toast.makeText(this, "Đã dừng săn!", Toast.LENGTH_SHORT).show()
    }

    private fun startRealtimeClock() {
        clockJob?.cancel()
        clockJob = serviceScope.launch {
            while (isActive) {
                try {
                    val formattedTime = TimeHelper.getCurrentTimeFormatted()
                    val isConnected = TayGanAccessibilityService.isConnected()
                    val isHunting = TayGanAccessibilityService.isHuntingActive
                    val currentStatus = TayGanAccessibilityService.currentStatusFlow.value

                    expandedView?.findViewById<TextView>(R.id.tvRealtimeClock)?.text = formattedTime
                    val dot = expandedView?.findViewById<View>(R.id.dotStatus)
                    val statusText = expandedView?.findViewById<TextView>(R.id.tvStatusText)

                    minimizedBubbleView?.findViewById<TextView>(R.id.tvBubbleClock)?.text = formattedTime
                    val bubbleDot = minimizedBubbleView?.findViewById<View>(R.id.bubbleStatusDot)
                    val bubbleStatus = minimizedBubbleView?.findViewById<TextView>(R.id.tvBubbleStatus)

                    if (isHunting && currentStatus.isNotBlank()) {
                        statusText?.text = currentStatus
                        bubbleStatus?.text = currentStatus
                        dot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_connected)
                        bubbleDot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_connected)
                    } else if (isConnected) {
                        dot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_connected)
                        bubbleDot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_connected)
                        val apiStatus = if (sessionManager.isLoggedIn) " + API Sẵn sàng" else ""
                        statusText?.text = "Service đã kết nối$apiStatus"
                        bubbleStatus?.text = "Sẵn sàng săn mã"
                    } else {
                        dot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_disconnected)
                        bubbleDot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_disconnected)
                        statusText?.text = "Service chưa kết nối (Chạm để bật)"
                        bubbleStatus?.text = "Chưa bật Trợ năng"
                    }
                } catch (_: Exception) {}

                delay(100)
            }
        }
    }

    private fun removeViews() {
        try {
            if (expandedView != null && expandedView?.isAttachedToWindow == true) {
                windowManager.removeView(expandedView)
            }
            if (minimizedBubbleView != null && minimizedBubbleView?.isAttachedToWindow == true) {
                windowManager.removeView(minimizedBubbleView)
            }
        } catch (_: Exception) {}
    }
}
