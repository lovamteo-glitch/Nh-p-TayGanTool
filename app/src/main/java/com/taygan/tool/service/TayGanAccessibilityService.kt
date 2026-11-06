package com.taygan.tool.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class TayGanAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TayGanAccessService"
        var instance: TayGanAccessibilityService? = null
            private set

        private val _serviceStatusFlow = MutableStateFlow(false)
        val serviceStatusFlow = _serviceStatusFlow.asStateFlow()

        fun isConnected(): Boolean = instance != null

        // Hunting configs
        var targetVoucherCode: String = ""
        var autoPlaceOrder: Boolean = true
        var isHuntingActive: Boolean = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var spamJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceStatusFlow.value = true
        Log.d(TAG, "TayGanAccessibilityService connected with Human Stealth Engine!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHuntingActive || event == null) return
        executeHuntStep()
    }

    override fun onInterrupt() {
        Log.d(TAG, "TayGanAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _serviceStatusFlow.value = false
        isHuntingActive = false
    }

    /**
     * Khởi động quá trình săn sale với thuật toán mô phỏng vân tay và độ trễ tự nhiên của người thật
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true

        vibrateAlert(120)
        Log.d(TAG, "Triggering hunting with code: $voucherCode, autoOrder: $placeOrder (Human Stealth Active)")

        spamJob?.cancel()
        spamJob = serviceScope.launch {
            // Chu kỳ quét tối đa 40 nhịp với độ trễ jitter ngẫu nhiên
            for (i in 0 until 45) {
                if (!isHuntingActive) break
                executeHuntStep()

                // Độ trễ ngẫu nhiên sinh học (Human Jitter): 60ms - 95ms thay vì máy móc 50ms cố định
                val humanJitterDelay = Random.nextLong(60, 95)
                delay(humanJitterDelay)
            }
        }
    }

    fun stopHunting() {
        isHuntingActive = false
        spamJob?.cancel()
        Log.d(TAG, "Hunting stopped.")
    }

    private fun executeHuntStep() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 1. Điền mã voucher vào ô nhập (nếu có ô nhập)
            if (targetVoucherCode.isNotEmpty()) {
                val inputNodes = findNodesByTextOrHint(rootNode, listOf("nhập mã", "voucher", "mã giảm giá", "coupon"))
                for (node in inputNodes) {
                    if (node.isEditable) {
                        val arguments = Bundle()
                        arguments.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            targetVoucherCode
                        )
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        Log.d(TAG, "Auto filled voucher code: $targetVoucherCode")
                        break
                    }
                }
            }

            // 2. Chạm tự nhiên nút "Áp dụng"
            clickFirstMatchingNode(
                rootNode,
                listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn", "Lưu mã", "Dùng ngay")
            )

            // 3. Chạm tự nhiên nút "Đồng ý" / "Xác nhận"
            clickFirstMatchingNode(
                rootNode,
                listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "XÁC NHẬN", "OK")
            )

            // 4. Chạm tự nhiên nút "Đặt hàng" / "Thanh toán"
            if (autoPlaceOrder) {
                val clickedOrder = clickFirstMatchingNode(
                    rootNode,
                    listOf("Đặt hàng", "ĐẶT HÀNG", "Mua hàng", "MUA HÀNG", "Thanh toán", "THANH TOÁN")
                )
                if (clickedOrder) {
                    vibrateAlert(180)
                    Log.d(TAG, "Successfully triggered Human Touch on Order button!")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error executing hunt step: ${e.message}")
        }
    }

    private fun clickFirstMatchingNode(rootNode: AccessibilityNodeInfo, targetTexts: List<String>): Boolean {
        for (targetText in targetTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
            for (node in nodes) {
                if (performHumanLikeClick(node)) {
                    Log.d(TAG, "Human-like click dispatched for: $targetText")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Thuật toán chạm vân tay người thật (Human Fingerprint Emulation):
     * - KHÔNG chạm vào tâm toán học cố định 100%
     * - Tạo độ lệch ngẫu nhiên (Offset Jitter) trong phạm vi nút bấm
     * - Tạo độ trượt vi mô 1-2 pixel mô phỏng da đầu ngón tay biến dạng khi ấn xuống kính
     * - Thời gian tiếp xúc da (Contact Duration) biến thiên tự nhiên 45ms - 75ms
     */
    private fun performHumanLikeClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 10 && bounds.height() > 10) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()

            // Vùng an toàn 60% bên trong nút (tránh mép nút)
            val safeRadiusX = width * 0.25f
            val safeRadiusY = height * 0.25f

            // Tạo tọa độ ngẫu nhiên rơi vào vân tay người bấm
            val randomOffsetX = (Random.nextFloat() * 2f - 1f) * safeRadiusX
            val randomOffsetY = (Random.nextFloat() * 2f - 1f) * safeRadiusY

            val startX = bounds.centerX() + randomOffsetX
            val startY = bounds.centerY() + randomOffsetY

            // Độ trượt vi mô (Flesh Micro-Drift): 1.0 - 2.5 pixel theo hướng ấn
            val driftX = startX + (Random.nextFloat() * 2f - 1f) * 1.8f
            val driftY = startY + (Random.nextFloat() * 2f - 1f) * 1.8f

            // Thời gian ấn giữ tự nhiên của ngón cái (45ms - 75ms)
            val contactDuration = Random.nextLong(45, 75)

            // Bắn cử chỉ cảm ứng phần cứng thực tế (Hardware MotionEvent)
            dispatchHumanTapGesture(startX, startY, driftX, driftY, contactDuration)
            return true
        }

        // Dự phòng: Nếu bounds không hợp lệ mới dùng action click
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    /**
     * Bắn MotionEvent thật qua đường kính cử chỉ dispatchGesture
     */
    private fun dispatchHumanTapGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun findNodesByTextOrHint(node: AccessibilityNodeInfo, keywords: List<String>): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(currentNode: AccessibilityNodeInfo?) {
            if (currentNode == null) return

            val text = currentNode.text?.toString()?.lowercase() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentNode.hintText?.toString()?.lowercase() ?: ""
            } else ""

            if (keywords.any { text.contains(it) || hint.contains(it) }) {
                result.add(currentNode)
            }

            for (i in 0 until currentNode.childCount) {
                traverse(currentNode.getChild(i))
            }
        }

        traverse(node)
        return result
    }

    private fun vibrateAlert(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
