package com.taygan.tool

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taygan.tool.service.FloatingWindowService
import com.taygan.tool.service.TayGanAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvDeviceIdValue: TextView
    private lateinit var tvAccessibilityTitle: TextView
    private lateinit var tvAccessibilitySubtitle: TextView
    private lateinit var iconA11yBox: FrameLayout
    private lateinit var tvLiveLog: TextView
    private lateinit var btnSendLog: TextView
    private lateinit var btnSettings: FrameLayout

    private lateinit var cardDeviceId: View
    private lateinit var cardAccessibility: View
    private lateinit var cardOpenTool: View
    private lateinit var cardSaveVoucher: View
    private lateinit var cardLicense: View

    private var currentDeviceId = "4e69c3a05444f43b"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadDeviceInfo()
        setupListeners()
        startLiveLogObserver()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityUI()
    }

    private fun initViews() {
        tvDeviceIdValue = findViewById(R.id.tvDeviceIdValue)
        tvAccessibilityTitle = findViewById(R.id.tvAccessibilityTitle)
        tvAccessibilitySubtitle = findViewById(R.id.tvAccessibilitySubtitle)
        iconA11yBox = findViewById(R.id.iconA11yBox)
        tvLiveLog = findViewById(R.id.tvLiveLog)
        btnSendLog = findViewById(R.id.btnSendLog)
        btnSettings = findViewById(R.id.btnSettings)

        cardDeviceId = findViewById(R.id.cardDeviceId)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardOpenTool = findViewById(R.id.cardOpenTool)
        cardSaveVoucher = findViewById(R.id.cardSaveVoucher)
        cardLicense = findViewById(R.id.cardLicense)
    }

    @SuppressLint("HardwareIds")
    private fun loadDeviceInfo() {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (!id.isNullOrBlank()) {
            currentDeviceId = id
        }
        tvDeviceIdValue.text = currentDeviceId
    }

    private fun setupListeners() {
        // 1. Sao chép Device ID khi bấm vào Card
        cardDeviceId.setOnClickListener {
            copyToClipboard("Device ID", currentDeviceId)
            Toast.makeText(this, "Đã sao chép Device ID: $currentDeviceId", Toast.LENGTH_SHORT).show()
        }

        // 2. Bật / Tắt Quyền Trợ Năng (Accessibility)
        cardAccessibility.setOnClickListener {
            val isRunning = TayGanAccessibilityService.isConnected()
            if (isRunning) {
                AlertDialog.Builder(this)
                    .setTitle("Quản lý Trợ Năng")
                    .setMessage("Dịch vụ Trợ Năng đang BẬT và sẵn sàng săn sale.\nBạn có muốn mở Cài đặt để tắt không?")
                    .setPositiveButton("Vào Cài Đặt") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            } else {
                handleEnableAccessibility()
            }
        }

        // 3. Mở Tool săn mã (Bảng nổi Overlay)
        cardOpenTool.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác!", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            val intent = Intent(this, FloatingWindowService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Đã mở Tool săn mã thành công!", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                try {
                    startService(intent)
                } catch (err: Throwable) {
                    Toast.makeText(this, "Lỗi mở tool: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 4. Lưu Voucher qua Web / API
        cardSaveVoucher.setOnClickListener {
            startActivity(Intent(this, ShopeeLoginActivity::class.java))
        }

        // 5. Nút Cài Đặt (Bánh răng)
        btnSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Tay Gân Tool v9")
                .setMessage("Phiên bản: 1.0.0 (v9)\nKênh hỗ trợ: t.me/Tanpham91\nTrạng thái bản quyền: Đang sử dụng (30 ngày)")
                .setPositiveButton("Mở Telegram") { _, _ ->
                    try {
                        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Tanpham91"))
                        startActivity(telegramIntent)
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Đóng", null)
                .show()
        }

        // 6. Nút Gửi Log
        btnSendLog.setOnClickListener {
            val logText = tvLiveLog.text.toString()
            copyToClipboard("TayGan Logs", logText)
            Toast.makeText(this, "Đã sao chép Log vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEnableAccessibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            AlertDialog.Builder(this)
                .setTitle("Cấp quyền Trợ Năng (Accessibility)")
                .setMessage(
                    "⚠️ LƯU Ý CHO ANDROID 13 & 14:\n\n" +
                    "Nếu điện thoại hiện thông báo:\n" +
                    "\"Chế độ cài đặt bị hạn chế\"\n\n" +
                    "👉 Cách gỡ chặn cực nhanh (15 giây):\n" +
                    "1. Bấm nút [Gỡ chặn 3 chấm] bên dưới để mở Thông tin ứng dụng.\n" +
                    "2. Nhìn góc trên bên phải, bấm vào [Dấu 3 chấm ⋮].\n" +
                    "3. Chọn [Cho phép cài đặt bị hạn chế] (quét vân tay xác nhận).\n" +
                    "4. Quay lại đây bấm nút [Vào Cài Đặt Trợ Năng] và BẬT công tắc 'Tay Gân Tool'!"
                )
                .setPositiveButton("Vào Cài Đặt Trợ Năng") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton("Gỡ chặn 3 chấm") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Không thể mở cài đặt: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Đóng", null)
                .show()
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Vui lòng tìm 'Tay Gân Tool' và BẬT công tắc!", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAccessibilityUI() {
        val isRunning = TayGanAccessibilityService.isConnected()
        if (isRunning) {
            tvAccessibilityTitle.text = "Tắt trợ năng"
            tvAccessibilitySubtitle.text = "Ngừng quyền truy cập"
            iconA11yBox.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_rose)
        } else {
            tvAccessibilityTitle.text = "Bật hỗ trợ tiếp cận"
            tvAccessibilitySubtitle.text = "Cấp quyền cho app"
            iconA11yBox.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_rose)
        }
    }

    private fun startLiveLogObserver() {
        lifecycleScope.launch {
            TayGanAccessibilityService.logFlow.collect { logs ->
                tvLiveLog.text = logs
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                updateAccessibilityUI()
                delay(1000)
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
