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
        Log.d(TAG, "TayGanAccessibilityService connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHuntingActive || event == null) return

        // When hunting is active, immediately check the window for targets
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
     * Start the hunting sequence immediately (called by timer or manual start)
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true

        vibrateAlert(150)
        Log.d(TAG, "Triggering hunting with code: $voucherCode, autoOrder: $placeOrder")

        // Launch aggressive loop to scan & click
        spamJob?.cancel()
        spamJob = serviceScope.launch {
            for (i in 0 until 50) { // Aggressive 50 bursts over 2.5 seconds
                if (!isHuntingActive) break
                executeHuntStep()
                delay(50)
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
            // 1. Try finding and filling Voucher EditText if present
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
                        Log.d(TAG, "Auto filled voucher code into input: $targetVoucherCode")
                        break
                    }
                }
            }

            // 2. Click "Áp dụng" (Apply voucher)
            clickFirstMatchingNode(
                rootNode,
                listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn", "Lưu mã", "Dùng ngay")
            )

            // 3. Click "Đồng ý" / "OK" on voucher sheet
            clickFirstMatchingNode(
                rootNode,
                listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "XÁC NHẬN", "OK")
            )

            // 4. Auto place order if enabled
            if (autoPlaceOrder) {
                val clickedOrder = clickFirstMatchingNode(
                    rootNode,
                    listOf("Đặt hàng", "ĐẶT HÀNG", "Mua hàng", "MUA HÀNG", "Thanh toán", "THANH TOÁN")
                )
                if (clickedOrder) {
                    vibrateAlert(200)
                    Log.d(TAG, "Successfully clicked Order / Checkout button!")
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
                if (performClickOnNode(node)) {
                    Log.d(TAG, "Clicked node matching text: $targetText")
                    return true
                }
            }
        }
        return false
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // Check if node itself is clickable
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }

        // Try parent clickable
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            parent = parent.parent
        }

        // Fallback: Perform simulated tap gesture at node center coordinates
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            dispatchTapGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return true
        }

        return false
    }

    /**
     * Dispatches simulated touch tap at exact screen coordinates (sub-millisecond tap)
     */
    fun dispatchTapGesture(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
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
