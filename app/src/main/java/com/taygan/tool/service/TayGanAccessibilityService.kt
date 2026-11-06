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

    // Quản lý trạng thái săn voucher
    private var hasSelectedVoucher = false
    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceStatusFlow.value = true
        Log.d(TAG, "TayGanAccessibilityService connected - Accurate Shopee Navigation Active!")
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
     * Kích hoạt quy trình săn mã
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true
        hasSelectedVoucher = false
        lastActionTime = 0L

        vibrateAlert(120)
        Log.d(TAG, "Triggered hunting: Code='$voucherCode', AutoOrder=$placeOrder")

        spamJob?.cancel()
        spamJob = serviceScope.launch {
            for (step in 0 until 150) {
                if (!isHuntingActive) break
                executeSmartHuntLoop()
                delay(Random.nextLong(70, 110))
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
     * Thuật toán điều hướng chính xác giữa Trang Thanh Toán và Popup Chọn Voucher
     */
    private fun executeSmartHuntLoop() {
        val rootNode = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        try {
            val isPopup = isVoucherSelectionPopup(rootNode)
            val isCheckout = isCheckoutScreen(rootNode)

            // ================= 1. KHI ĐANG Ở TRANG THANH TOÁN (CHECKOUT) =================
            if (isCheckout) {
                // TUYỆT ĐỐI KHÔNG BẤM BACK TRÊN TRANG THANH TOÁN!

                if (!hasSelectedVoucher) {
                    // Chưa chọn được voucher -> Bấm vào dòng "Shopee Voucher" để mở bảng chọn mã!
                    if (now - lastActionTime > 250) { // Giữ nhịp 250ms tránh nhấp đúp
                        lastActionTime = now
                        Log.d(TAG, "Đang ở trang Thanh toán -> Bấm vào mục 'Shopee Voucher' ở dưới...")
                        clickShopeeVoucherRow(rootNode)
                    }
                } else {
                    // Đã chọn xong voucher -> Bấm nút "Đặt hàng" / "Thanh toán" chốt đơn ngay!
                    if (autoPlaceOrder) {
                        Log.d(TAG, "Đã áp voucher xong -> Bấm nút 'Đặt hàng'!")
                        val clicked = clickFirstMatchingNode(
                            rootNode,
                            listOf("Đặt hàng", "ĐẶT HÀNG", "Thanh toán", "THANH TOÁN")
                        )
                        if (clicked) {
                            vibrateAlert(200)
                        }
                    }
                }
                return
            }

            // ================= 2. KHI ĐANG Ở BẢNG CHỌN VOUCHER (POPUP / SHEET) =================
            if (isPopup) {
                // Bước 2.1: Quét xem mã cần săn đã xuất hiện trong danh sách chưa
                val voucherNode = findMatchingVoucherNode(rootNode, targetVoucherCode)
                if (voucherNode != null) {
                    Log.d(TAG, "Đã tìm thấy mã mục tiêu trong danh sách: $targetVoucherCode -> Bấm chọn!")
                    performHumanLikeClick(voucherNode)
                    hasSelectedVoucher = true
                    vibrateAlert(80)

                    // Bấm nút "Đồng ý" / "Xác nhận" để lưu lựa chọn và đóng popup
                    clickFirstMatchingNode(
                        rootNode,
                        listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "XÁC NHẬN", "OK", "Áp dụng")
                    )
                    return
                }

                // Bước 2.2: Nếu có ô nhập mã dạng text (VD SVIPDD...), tự động điền mã
                if (isAlphanumericCode(targetVoucherCode)) {
                    val inputFilled = tryFillVoucherEditText(rootNode, targetVoucherCode)
                    if (inputFilled) {
                        clickFirstMatchingNode(rootNode, listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn"))
                        clickFirstMatchingNode(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "OK"))
                        hasSelectedVoucher = true
                        return
                    }
                }

                // Bước 2.3: NẾU CHƯA HIỆN MÃ TRONG DANH SÁCH:
                // Thoát bảng chọn mã ra để về lại trang thanh toán, sau đó mở lại để Shopee tải mới (Refresh)!
                if (now - lastActionTime > 300) {
                    lastActionTime = now
                    Log.d(TAG, "Chưa thấy mã trong popup -> Đóng popup để làm mới danh sách...")
                    // Thử bấm nút Đóng (X) hoặc mũi tên quay lại của popup trước
                    val closedByButton = clickFirstMatchingNode(
                        rootNode,
                        listOf("Trở lại", "Quay lại", "Close", "Đóng")
                    )
                    if (!closedByButton) {
                        // Nếu không thấy nút đóng thì dùng nút Back để đóng popup
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
                return
            }

            // ================= 3. TRƯỜNG HỢP DỰ PHÒNG =================
            // Nếu đã chọn xong voucher và nhìn thấy nút Đặt hàng bất kỳ đâu
            if (hasSelectedVoucher && autoPlaceOrder) {
                clickFirstMatchingNode(
                    rootNode,
                    listOf("Đặt hàng", "ĐẶT HÀNG", "Thanh toán", "THANH TOÁN")
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi điều hướng: ${e.message}")
        }
    }

    /**
     * Nhận diện Trang Thanh Toán:
     * Luôn có nút "Đặt hàng" hoặc tiêu đề "Thanh toán".
     */
    private fun isCheckoutScreen(rootNode: AccessibilityNodeInfo): Boolean {
        val hasOrderBtn = rootNode.findAccessibilityNodeInfosByText("Đặt hàng").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐẶT HÀNG").isNotEmpty()
        val hasCheckoutTitle = rootNode.findAccessibilityNodeInfosByText("Thanh toán").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Tổng thanh toán").isNotEmpty()
        return hasOrderBtn || hasCheckoutTitle
    }

    /**
     * Nhận diện Bảng Chọn Voucher (Popup):
     * Có nút "Đồng ý" hoặc tiêu đề "Chọn Shopee Voucher", và KHÔNG PHẢI là trang Thanh toán (không có nút "Đặt hàng").
     */
    private fun isVoucherSelectionPopup(rootNode: AccessibilityNodeInfo): Boolean {
        val hasOrderBtn = rootNode.findAccessibilityNodeInfosByText("Đặt hàng").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐẶT HÀNG").isNotEmpty()
        if (hasOrderBtn) {
            return false // Có nút Đặt hàng thì 100% là trang Thanh toán, không phải popup!
        }

        val hasConfirmBtn = rootNode.findAccessibilityNodeInfosByText("Đồng ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐỒNG Ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Xác nhận").isNotEmpty()
        val hasVoucherTitle = rootNode.findAccessibilityNodeInfosByText("Chọn Shopee Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Nhập mã Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Mã Giảm Giá").isNotEmpty()

        return hasConfirmBtn || hasVoucherTitle
    }

    /**
     * Bấm chính xác vào dòng "Shopee Voucher" ở cuối trang Thanh toán
     */
    private fun clickShopeeVoucherRow(rootNode: AccessibilityNodeInfo): Boolean {
        val keywords = listOf(
            "Shopee Voucher",
            "Miễn Phí Vận Chuyển",
            "Chọn hoặc nhập mã",
            "Mã giảm giá",
            "Voucher của Shop"
        )

        for (kw in keywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                val clickable = findClickableAncestor(node) ?: node
                if (performHumanLikeClick(clickable)) {
                    Log.d(TAG, "Đã bấm vào dòng Shopee Voucher thành công (khóa: '$kw')")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Tìm chính xác thẻ voucher khớp với mã mà người dùng đã chọn
     */
    private fun findMatchingVoucherNode(rootNode: AccessibilityNodeInfo, targetCode: String): AccessibilityNodeInfo? {
        if (targetCode.isBlank()) return null
        val cleanTarget = targetCode.trim().lowercase()

        // 1. Tìm khớp chính xác toàn phần
        val directMatches = rootNode.findAccessibilityNodeInfosByText(targetCode)
        for (node in directMatches) {
            val clickableParent = findClickableAncestor(node)
            return clickableParent ?: node
        }

        // 2. Tìm thông minh theo các từ khóa đặc trưng (VD: "giảm 35%", "150k", "giảm 50%")
        val keywords = extractKeywords(cleanTarget)
        if (keywords.isNotEmpty()) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""

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

        val percentMatch = Regex("(\\d+%)").find(lower)
        percentMatch?.value?.let { tokens.add(it) }

        val moneyMatch = Regex("(\\d+k)").find(lower)
        moneyMatch?.value?.let { tokens.add(it) }

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
     * Bắn cử chỉ cảm ứng mô phỏng vân tay người thật
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
                val vibratorManager = getSystemService(Context.VIBRATOR_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
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
