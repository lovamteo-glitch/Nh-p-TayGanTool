package com.taygan.tool

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taygan.tool.api.ShopeeApiService
import com.taygan.tool.api.ShopeeSessionManager
import com.taygan.tool.service.FloatingWindowService
import com.taygan.tool.service.TayGanAccessibilityService
import com.taygan.tool.utils.PrefManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantBattery: Button
    private lateinit var btnToggleFloatingWindow: Button
    private lateinit var btnStopAllServices: Button

    // Shopee API Session
    private lateinit var tvShopeeAccountStatus: TextView
    private lateinit var btnShopeeLogin: Button
    private lateinit var btnPasteCookie: Button
    private lateinit var btnSyncCloudVouchers: Button

    private lateinit var sessionManager: ShopeeSessionManager
    private lateinit var prefManager: PrefManager
    private lateinit var apiService: ShopeeApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = ShopeeSessionManager.getInstance(this)
        prefManager = PrefManager(this)
        apiService = ShopeeApiService(this)

        initViews()
        setupListeners()
        startStatusObserver()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateShopeeSessionStatus()
    }

    private fun initViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnGrantBattery = findViewById(R.id.btnGrantBattery)
        btnToggleFloatingWindow = findViewById(R.id.btnToggleFloatingWindow)
        btnStopAllServices = findViewById(R.id.btnStopAllServices)

        tvShopeeAccountStatus = findViewById(R.id.tvShopeeAccountStatus)
        btnShopeeLogin = findViewById(R.id.btnShopeeLogin)
        btnPasteCookie = findViewById(R.id.btnPasteCookie)
        btnSyncCloudVouchers = findViewById(R.id.btnSyncCloudVouchers)
    }

    private fun setupListeners() {
        btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Quyền Cửa sổ nổi đã được cấp!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Vui lòng tìm 'Tay Gân Tool' và BẬT công tắc!", Toast.LENGTH_LONG).show()
        }

        btnGrantBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        btnShopeeLogin.setOnClickListener {
            startActivity(Intent(this, ShopeeLoginActivity::class.java))
        }

        btnPasteCookie.setOnClickListener {
            showPasteCookieDialog()
        }

        btnSyncCloudVouchers.setOnClickListener {
            syncVouchersFromCloud()
        }

        btnToggleFloatingWindow.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền Cửa sổ nổi trước!", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            // Start Floating Service
            val intent = Intent(this, FloatingWindowService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Đã mở bảng điều khiển nổi!", Toast.LENGTH_SHORT).show()

            // Minimize MainActivity so user sees floating panel
            moveTaskToBack(true)
        }

        btnStopAllServices.setOnClickListener {
            val intent = Intent(this, FloatingWindowService::class.java)
            stopService(intent)
            Toast.makeText(this, "Đã đóng cửa sổ nổi!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateShopeeSessionStatus() {
        if (sessionManager.isLoggedIn) {
            tvShopeeAccountStatus.text = "✓ Đã kết nối Shopee (Sẵn sàng lưu mã API ngầm)"
            tvShopeeAccountStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            btnShopeeLogin.text = "Đăng nhập lại"
        } else {
            tvShopeeAccountStatus.text = "Chưa kết nối tài khoản Shopee (Chưa thể lưu mã API)"
            tvShopeeAccountStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            btnShopeeLogin.text = "Đăng nhập Shopee"
        }
    }

    private fun showPasteCookieDialog() {
        val input = EditText(this).apply {
            hint = "Dán chuỗi Cookie Shopee vào đây..."
            setText(sessionManager.cookie)
        }

        AlertDialog.Builder(this)
            .setTitle("Nhập Cookie Shopee")
            .setMessage("Dán toàn bộ Cookie (có chứa csrftoken và SPC_EC):")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    sessionManager.cookie = text
                    updateShopeeSessionStatus()
                    Toast.makeText(this, "Đã lưu Cookie thành công!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun syncVouchersFromCloud() {
        btnSyncCloudVouchers.isEnabled = false
        btnSyncCloudVouchers.text = "Đang tải mã từ máy chủ NKQUI..."

        lifecycleScope.launch {
            val result = apiService.fetchRemoteVoucherConfig()
            btnSyncCloudVouchers.isEnabled = true
            btnSyncCloudVouchers.text = "Cập nhật mã từ Server (Kèm Signature API)"

            result.onSuccess { vouchers ->
                prefManager.saveVouchers(vouchers)
                Toast.makeText(
                    this@MainActivity,
                    "Đã tải thành công ${vouchers.size} mã kèm chữ ký API!",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Không thể kết nối máy chủ voucher: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (hasOverlay) {
            tvOverlayStatus.text = "Đã cấp quyền ✓"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            btnGrantOverlay.text = "Đã cấp"
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.alpha = 0.5f
        } else {
            tvOverlayStatus.text = "Chưa cấp quyền"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            btnGrantOverlay.text = "Cấp quyền"
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.alpha = 1.0f
        }

        val isAccessibilityActive = TayGanAccessibilityService.isConnected()
        if (isAccessibilityActive) {
            tvAccessibilityStatus.text = "Đã bật dịch vụ ✓"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            btnGrantAccessibility.text = "Đã bật"
            btnGrantAccessibility.isEnabled = false
            btnGrantAccessibility.alpha = 0.5f
        } else {
            tvAccessibilityStatus.text = "Chưa bật dịch vụ"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            btnGrantAccessibility.text = "Bật ngay"
            btnGrantAccessibility.isEnabled = true
            btnGrantAccessibility.alpha = 1.0f
        }
    }

    private fun startStatusObserver() {
        lifecycleScope.launch {
            while (isActive) {
                updatePermissionStatus()
                delay(1000)
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Đã tắt tối ưu hóa pin!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
