package com.taygan.tool.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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

        startForegroundServiceNotification()
        initViews()
        startRealtimeClock()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        clockJob?.cancel()
        huntCountdownJob?.cancel()
        removeViews()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tay Gân Tool Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị bảng điều khiển nổi để săn sale"
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
            .setContentTitle("Tay Gân Tool đang hoạt động")
            .setContentText("Bảng điều khiển nổi & Lưu mã API Shopee đang mở")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initViews() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 150
        }

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 120
        }

        val inflater = LayoutInflater.from(this)
        expandedView = inflater.inflate(R.layout.layout_floating_window, null)
        minimizedBubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        setupExpandedViewLogic(expandedParams)
        setupBubbleViewLogic(bubbleParams)

        windowManager.addView(expandedView, expandedParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExpandedViewLogic(params: WindowManager.LayoutParams) {
        val root = expandedView ?: return

        val headerBar = root.findViewById<View>(R.id.headerBar)
        headerBar.setOnTouchListener { _, event ->
            handleTouchDrag(event, params, expandedView)
        }

        root.findViewById<View>(R.id.btnCloseWindow).setOnClickListener {
            stopSelf()
        }

        root.findViewById<View>(R.id.btnMinimize).setOnClickListener {
            minimizeToBubble()
        }

        val tabHuntCode = root.findViewById<TextView>(R.id.tabHuntCode)
        val tabDealSvip = root.findViewById<TextView>(R.id.tabDealSvip)
        tabHuntCode.setOnClickListener {
            tabHuntCode.setBackgroundResource(R.drawable.bg_tab_active)
            tabHuntCode.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            tabDealSvip.setBackgroundResource(R.drawable.bg_tab_inactive)
            tabDealSvip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            prefManager.activeTab = 0
        }
        tabDealSvip.setOnClickListener {
            tabDealSvip.setBackgroundResource(R.drawable.bg_tab_active)
            tabDealSvip.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            tabHuntCode.setBackgroundResource(R.drawable.bg_tab_inactive)
            tabHuntCode.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            prefManager.activeTab = 1
            Toast.makeText(this, "Đã chuyển sang tab Deal SVIP (API Hunter)", Toast.LENGTH_SHORT).show()
        }

        val previewRoot = root.findViewById<View>(R.id.llSelectedVoucherPreview)
        val tvPreviewBadge = root.findViewById<TextView>(R.id.tvPreviewBadge)
        val tvPreviewTitle = root.findViewById<TextView>(R.id.tvPreviewTitle)
        val ivArrow = root.findViewById<ImageView>(R.id.ivExpandCollapseArrow)
        val rvVouchers = root.findViewById<RecyclerView>(R.id.rvVouchers)

        currentVouchers = prefManager.getVouchers()
        root.findViewById<TextView>(R.id.tvVoucherCountBadge).text = "${currentVouchers.size} mã"

        voucherAdapter = VoucherAdapter(currentVouchers) { selectedItem ->
            tvPreviewTitle.text = selectedItem.code
            tvPreviewBadge.text = if (selectedItem.isApiClaimable) "API" else if (selectedItem.badgeType == BadgeType.VIP) "VIP" else "SALE"
            prefManager.saveVouchers(currentVouchers)
        }

        val initialSelected = voucherAdapter.getSelectedItem()
        if (initialSelected != null) {
            tvPreviewTitle.text = initialSelected.code
            tvPreviewBadge.text = if (initialSelected.isApiClaimable) "API" else "VIP"
        }

        rvVouchers.layoutManager = LinearLayoutManager(this)
        rvVouchers.adapter = voucherAdapter

        previewRoot.setOnClickListener {
            if (rvVouchers.visibility == View.VISIBLE) {
                rvVouchers.visibility = View.GONE
                ivArrow.setImageResource(R.drawable.ic_arrow_down)
            } else {
                rvVouchers.visibility = View.VISIBLE
                ivArrow.setImageResource(R.drawable.ic_arrow_up)
            }
        }

        val switchHuntByPercent = root.findViewById<SwitchCompat>(R.id.switchHuntByPercent)
        val switchAutoOrder = root.findViewById<SwitchCompat>(R.id.switchAutoOrder)
        val switchHuntByTime = root.findViewById<SwitchCompat>(R.id.switchHuntByTime)
        val timeContainer = root.findViewById<View>(R.id.llTimeInputsContainer)

        switchHuntByPercent.isChecked = prefManager.huntByPercent
        switchAutoOrder.isChecked = prefManager.autoOrder
        switchHuntByTime.isChecked = prefManager.huntByTime

        switchHuntByPercent.setOnCheckedChangeListener { _, isChecked ->
            prefManager.huntByPercent = isChecked
        }
        switchAutoOrder.setOnCheckedChangeListener { _, isChecked ->
            prefManager.autoOrder = isChecked
        }
        switchHuntByTime.setOnCheckedChangeListener { _, isChecked ->
            prefManager.huntByTime = isChecked
            timeContainer.alpha = if (isChecked) 1.0f else 0.4f
        }

        val etHour = root.findViewById<EditText>(R.id.etTargetHour)
        val etMinute = root.findViewById<EditText>(R.id.etTargetMinute)
        val etSecond = root.findViewById<EditText>(R.id.etTargetSecond)

        etHour.setText(String.format("%02d", prefManager.targetHour))
        etMinute.setText(String.format("%02d", prefManager.targetMinute))
        etSecond.setText(String.format("%02d", prefManager.targetSecond))

        val btnStart = root.findViewById<View>(R.id.btnStart)
        val tvStartText = root.findViewById<TextView>(R.id.tvStartText)
        val btnStop = root.findViewById<View>(R.id.btnStop)

        btnStart.setOnClickListener {
            val h = etHour.text.toString().toIntOrNull() ?: prefManager.targetHour
            val m = etMinute.text.toString().toIntOrNull() ?: prefManager.targetMinute
            val s = etSecond.text.toString().toIntOrNull() ?: prefManager.targetSecond
            prefManager.targetHour = h
            prefManager.targetMinute = m
            prefManager.targetSecond = s

            startHunting(tvStartText)
        }

        btnStop.setOnClickListener {
            stopHunting(tvStartText)
        }

        root.findViewById<View>(R.id.llServiceStatus).setOnClickListener {
            if (!TayGanAccessibilityService.isConnected()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                Toast.makeText(this, "Vui lòng tìm và BẬT 'Tay Gân Tool' trong Trợ năng", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleViewLogic(params: WindowManager.LayoutParams) {
        val bubble = minimizedBubbleView ?: return

        bubble.setOnTouchListener { _, event ->
            handleTouchDrag(event, params, minimizedBubbleView)
        }

        bubble.setOnClickListener {
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
                    windowManager.updateViewLayout(targetView, params)
                } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP -> {
                val diffX = Math.abs(event.rawX - initialTouchX)
                val diffY = Math.abs(event.rawY - initialTouchY)
                if (diffX < 10 && diffY < 10) {
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
            windowManager.removeView(expandedView)
        } catch (_: Exception) {}

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 120
        }

        windowManager.addView(minimizedBubbleView, bubbleParams)
    }

    private fun expandToWindow() {
        if (!isMinimized) return
        isMinimized = false
        try {
            windowManager.removeView(minimizedBubbleView)
        } catch (_: Exception) {}

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 150
        }

        windowManager.addView(expandedView, expandedParams)
    }

    private fun startHunting(tvStartText: TextView) {
        if (!TayGanAccessibilityService.isConnected()) {
            Toast.makeText(this, "Cảnh báo: Dịch vụ Trợ năng chưa bật!", Toast.LENGTH_LONG).show()
        }

        val selectedVoucher = voucherAdapter.getSelectedItem() ?: return
        val autoOrder = prefManager.autoOrder
        val huntByTime = prefManager.huntByTime

        isHuntingArmed = true
        tvStartText.text = "Đang chạy..."

        val triggerCombo = {
            serviceScope.launch {
                // Bước 1: Nếu voucher có chữ ký API và đã kết nối Shopee, bắn API lưu mã ngầm trước!
                if (selectedVoucher.isApiClaimable && sessionManager.isLoggedIn) {
                    tvStartText.text = "Lưu API..."
                    val apiResult = apiService.saveVoucherDirect(selectedVoucher)
                    when (apiResult) {
                        is SaveApiResult.Success -> {
                            Toast.makeText(this@FloatingWindowService, "API: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                        is SaveApiResult.Retry -> {
                            Toast.makeText(this@FloatingWindowService, "API Retry: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                        is SaveApiResult.Failed -> {
                            Toast.makeText(this@FloatingWindowService, "API: ${apiResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Bước 2: Tự động kích hoạt Accessibility Service điền mã & chốt đơn đặt hàng
                TayGanAccessibilityService.instance?.triggerHunting(selectedVoucher.code, autoOrder)
                tvStartText.text = "Đã bắn mã!"
            }
        }

        if (!huntByTime) {
            triggerCombo()
            Toast.makeText(this, "Đã kích hoạt săn ngay lập tức!", Toast.LENGTH_SHORT).show()
            return
        }

        val remainingMillis = TimeHelper.getRemainingMillis(
            prefManager.targetHour,
            prefManager.targetMinute,
            prefManager.targetSecond
        )

        Toast.makeText(
            this,
            "Đã đặt lịch săn lúc ${prefManager.targetHour}:${prefManager.targetMinute}:${prefManager.targetSecond}",
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
                val formattedTime = TimeHelper.getCurrentTimeFormatted()
                val isConnected = TayGanAccessibilityService.isConnected()

                expandedView?.findViewById<TextView>(R.id.tvRealtimeClock)?.text = formattedTime
                val dot = expandedView?.findViewById<View>(R.id.dotStatus)
                val statusText = expandedView?.findViewById<TextView>(R.id.tvStatusText)

                if (isConnected) {
                    dot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_connected)
                    val apiStatus = if (sessionManager.isLoggedIn) " + API Sẵn sàng" else ""
                    statusText?.text = "Service đã kết nối$apiStatus"
                } else {
                    dot?.backgroundTintList = ContextCompat.getColorStateList(this@FloatingWindowService, R.color.status_disconnected)
                    statusText?.text = "Service chưa kết nối (Chạm để bật)"
                }

                minimizedBubbleView?.findViewById<TextView>(R.id.tvBubbleClock)?.text = formattedTime
                val bubbleDot = minimizedBubbleView?.findViewById<View>(R.id.bubbleStatusDot)
                bubbleDot?.backgroundTintList = ContextCompat.getColorStateList(
                    this@FloatingWindowService,
                    if (isConnected) R.color.status_connected else R.color.status_disconnected
                )

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
