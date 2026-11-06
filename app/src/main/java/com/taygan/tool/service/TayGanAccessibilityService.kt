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

    // State machine cho quy trình: Vào voucher -> Quét -> Chưa thấy thì Back ra -> Vào lại -> Thấy mã thì Áp & Đặt hàng
    private var hasSelectedVoucher = false
    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceStatusFlow.value = true
        Log.d(TAG, "TayGanAccessibilityService connected with Auto-Refresh & Re-enter Engine!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHuntingActive || event == null) return
        executeSmartHuntLoop()
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
     * Khởi động quy trình săn sale thông minh
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true
        hasSelectedVoucher = false
        lastActionTime = System.currentTimeMillis()

        vibrateAlert(120)
        Log.d(TAG, "Triggering hunting: Code=$voucherCode, AutoOrder=$placeOrder")

        spamJob?.cancel()
        spamJob = serviceScope.launch {
            // Chu kỳ săn lặp liên tục mỗi 60ms - 100ms
            for (step in 0 until 120) { // Chạy bền bỉ trong 8-10 giây quanh mốc mở sale
                if (!isHuntingActive) break
                executeSmartHuntLoop()
                delay(Random.nextLong(65, 105))
            }
        }
    }

    fun stopHunting() {
        isHuntingActive = false
        hasSelectedVoucher = false
        spamJob?.cancel()
        Log.d(TAG, "Hunting stopped.")
    }

    /**
     * Vòng lặp săn thông minh (Auto Back & Re-enter):
     * 1. Xác định đang ở trang nào (Trang Thanh Toán hay Trang Chọn Voucher)
     * 2. Nếu ở trang Chọn Voucher:
     *    - Quét tìm đúng mã mục tiêu.
     *    - Nếu THẤY: Chọn mã -> Bấm "Đồng ý".
     *    - Nếu CHƯA THẤY: Bấm Back thoát ra ngay lập tức!
     * 3. Nếu ở trang Thanh Toán:
     *    - Nếu chưa có mã: Bấm lại vào "Shopee Voucher" để tải lại danh sách mới (Refresh)!
     *    - Nếu đã chọn xong mã: Bấm "Đặt hàng" chốt đơn!
     */
    private fun executeSmartHuntLoop() {
        val rootNode = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        try {
            val isInVoucherSheet = isCurrentlyInVoucherSheet(rootNode)
            val isInCheckoutPage = isCurrentlyInCheckoutPage(rootNode)

            // ================= TRƯỜNG HỢP 1: ĐANG Ở TRANG CHỌN VOUCHER =================
            if (isInVoucherSheet) {
                // Bước 1.1: Quét xem mã cần săn đã xuất hiện trong danh sách chưa
                val matchedVoucherNode = findMatchingVoucherNode(rootNode, targetVoucherCode)

                if (matchedVoucherNode != null) {
                    // ĐÃ TÌM THẤY ĐÚNG MÃ!
                    Log.d(TAG, "Đã tìm thấy đúng voucher mục tiêu: $targetVoucherCode")
                    performHumanLikeClick(matchedVoucherNode)
                    hasSelectedVoucher = true
                    vibrateAlert(80)

                    // Bấm nút "Đồng ý" / "Áp dụng" / "OK" để xác nhận
                    clickFirstMatchingNode(
                        rootNode,
                        listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "XÁC NHẬN", "OK", "Áp dụng", "Áp Dụng")
                    )
                    return
                }

                // Bước 1.2: Nếu có ô nhập mã, tự động điền mã nếu là mã nhập tay
                if (isAlphanumericCode(targetVoucherCode)) {
                    val inputFilled = tryFillVoucherEditText(rootNode, targetVoucherCode)
                    if (inputFilled) {
                        clickFirstMatchingNode(rootNode, listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn"))
                        clickFirstMatchingNode(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "OK"))
                        hasSelectedVoucher = true
                        return
                    }
                }

                // Bước 1.3: NẾU CHƯA HIỆN MÃ (Mã chưa nạp/chưa mở):
                // Đúng như chiến thuật pro: THOÁT RA để không bị kẹt ở danh sách cũ!
                if (now - lastActionTime > 180) { // Giữ nhịp 180ms tránh spam giật lag
                    lastActionTime = now
                    Log.d(TAG, "Chưa thấy mã $targetVoucherCode -> Thực hiện BACK ra ngoài để refresh...")
                    val clickedBack = clickFirstMatchingNode(rootNode, listOf("Trở lại", "Quay lại", "Close"))
                    if (!clickedBack) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
                return
            }

            // ================= TRƯỜNG HỢP 2: ĐANG Ở TRANG THANH TOÁN (CHECKOUT) =================
            if (isInCheckoutPage) {
                if (!hasSelectedVoucher) {
                    // Chưa chọn được mã: Lập tức bấm lại vào "Shopee Voucher" để mở lại danh sách mới!
                    if (now - lastActionTime > 150) {
                        lastActionTime = now
                        Log.d(TAG, "Đang ở trang thanh toán -> Bấm vào lại 'Shopee Voucher' để tải mới...")
                        clickFirstMatchingNode(
                            rootNode,
                            listOf(
                                "Shopee Voucher",
                                "Chọn hoặc nhập mã",
                                "Chọn mã giảm giá",
                                "Mã giảm giá",
                                "Voucher của Shop",
                                "Nhập mã voucher"
                            )
                        )
                    }
                } else {
                    // Đã chọn xong mã thành công: Bấm nút "Đặt hàng" chốt đơn ngay!
                    if (autoPlaceOrder) {
                        val clickedOrder = clickFirstMatchingNode(
                            rootNode,
                            listOf("Đặt hàng", "ĐẶT HÀNG", "Thanh toán", "THANH TOÁN", "Mua hàng", "MUA HÀNG")
                        )
                        if (clickedOrder) {
                            vibrateAlert(200)
                            Log.d(TAG, "Chốt đơn ĐẶT HÀNG thành công!")
                        }
                    }
                }
                return
            }

            // ================= TRƯỜNG HỢP 3: DỰ PHÒNG =================
            // Quét tìm nút "Đặt hàng" hoặc "Áp dụng" bất cứ đâu nếu đang hiển thị
            if (hasSelectedVoucher && autoPlaceOrder) {
                clickFirstMatchingNode(
                    rootNode,
                    listOf("Đặt hàng", "ĐẶT HÀNG", "Thanh toán", "THANH TOÁN")
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong executeSmartHuntLoop: ${e.message}")
        }
    }

    private fun isCurrentlyInVoucherSheet(rootNode: AccessibilityNodeInfo): Boolean {
        val hasConfirmBtn = hasAnyText(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "Áp dụng"))
        val hasVoucherTitle = hasAnyText(rootNode, listOf("Chọn Shopee Voucher", "Shopee Voucher", "Mã giảm giá", "Nhập mã Voucher"))
        return hasConfirmBtn && hasVoucherTitle
    }

    private fun isCurrentlyInCheckoutPage(rootNode: AccessibilityNodeInfo): Boolean {
        val hasOrderBtn = hasAnyText(rootNode, listOf("Đặt hàng", "ĐẶT HÀNG", "Thanh toán", "THANH TOÁN"))
        val hasCheckoutElements = hasAnyText(rootNode, listOf("Tổng thanh toán", "Phương thức thanh toán", "Shopee Voucher"))
        return hasOrderBtn || hasCheckoutElements
    }

    /**
     * Tìm chính xác thẻ voucher khớp với mã mà người dùng đã chọn
     */
    private fun findMatchingVoucherNode(rootNode: AccessibilityNodeInfo, targetCode: String): AccessibilityNodeInfo? {
        if (targetCode.isBlank()) return null
        val cleanTarget = targetCode.trim().lowercase()

        // 1. Tìm khớp trực tiếp
        val directMatches = rootNode.findAccessibilityNodeInfosByText(targetCode)
        for (node in directMatches) {
            val clickableParent = findClickableAncestor(node)
            return clickableParent ?: node
        }

        // 2. Tìm thông minh theo các từ khóa đặc trưng (Ví dụ: "giảm 35%", "150k", "giảm 50%")
        val keywords = extractKeywords(cleanTarget)
        if (keywords.isNotEmpty()) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""

                // Nếu chứa toàn bộ các từ khóa nhận diện của mã
                if (keywords.all { text.contains(it) || desc.contains(it) }) {
                    allNodes.add(node)
                }
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                }
            }
            traverse(rootNode)

            for (node in allNodes) {
                val clickable = findClickableAncestor(node)
                return clickable ?: node
            }
        }

        return null
    }

    private fun extractKeywords(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val lower = text.lowercase()

        // Bóc % (Ví dụ 35%, 50%)
        val percentMatch = Regex("(\\d+%)").find(lower)
        percentMatch?.value?.let { tokens.add(it) }

        // Bóc số tiền (Ví dụ 150k, 50k, 500k)
        val moneyMatch = Regex("(\\d+k)").find(lower)
        moneyMatch?.value?.let { tokens.add(it) }

        // Bóc tiền tố mã SVIP
        if (lower.startsWith("svip")) {
            tokens.add(lower.take(8))
        }

        return tokens
    }

    private fun isAlphanumericCode(code: String): Boolean {
        return code.length >= 6 && code.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun tryFillVoucherEditText(rootNode: AccessibilityNodeInfo, code: String): Boolean {
        val inputNodes = findNodesByTextOrHint(rootNode, listOf("nhập mã", "voucher", "mã giảm giá", "coupon"))
        for (node in inputNodes) {
            if (node.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    code
                )
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                return true
            }
        }
        return false
    }

    private fun hasAnyText(rootNode: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (text in texts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) return true
        }
        return false
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
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
     * Mô phỏng chạm ngón tay người thật với độ lệch vân tay và độ trượt vi mô
     */
    private fun performHumanLikeClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 10 && bounds.height() > 10) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()

            val safeRadiusX = width * 0.25f
            val safeRadiusY = height * 0.25f

            val randomOffsetX = (Random.nextFloat() * 2f - 1f) * safeRadiusX
            val randomOffsetY = (Random.nextFloat() * 2f - 1f) * safeRadiusY

            val startX = bounds.centerX() + randomOffsetX
            val startY = bounds.centerY() + randomOffsetY

            val driftX = startX + (Random.nextFloat() * 2f - 1f) * 1.8f
            val driftY = startY + (Random.nextFloat() * 2f - 1f) * 1.8f

            val contactDuration = Random.nextLong(45, 75)

            dispatchHumanTapGesture(startX, startY, driftX, driftY, contactDuration)
            return true
        }

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
