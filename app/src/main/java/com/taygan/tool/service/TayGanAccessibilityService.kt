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
import kotlinx.coroutines.isActive
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

        // Shopee package & View IDs (Chuẩn 100% trích xuất từ APK gốc vn.taygan.shopeevoucherbot)
        const val SHOPEE_PKG = "com.shopee.vn"
        const val ID_VOUCHER_ENTRY = "buttonCartPageUseVoucher"
        const val ID_VOUCHER_RADIO_PREFIX = "radioBtnVoucher_"
        const val ID_VOUCHER_SUBMIT = "btnOkVoucherSelectionSubmitSection"
        const val ID_PLACE_ORDER = "buttonPlaceOrder"
        const val ID_ACTION_BAR_BACK = "buttonActionBarIcon"
        const val ID_ORDER_POPUP_POSITIVE = "buttonDefaultPositive"
        const val ID_COIN_REDEEM = "labelRedeemCoins"
        const val ID_TOTAL_PRICE = "labelTotalPayment"
        const val ID_ORDER_TOTAL_PRICE = "labelOrderTotalPrice"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var workerJob: Job? = null

    // Quản lý trạng thái săn voucher
    @Volatile private var hasSelectedVoucher = false
    @Volatile private var lastActionTime = 0L
    @Volatile private var consecutiveOrderClicks = 0
    private var lastObservedWindowId = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceStatusFlow.value = true
        Log.d(TAG, "TayGanAccessibilityService connected - Native ID Engine Ready!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHuntingActive || event == null) return
        // Phản ứng tức thời khi có sự kiện giao diện thay đổi
        executeStep()
    }

    override fun onInterrupt() {
        Log.d(TAG, "TayGanAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _serviceStatusFlow.value = false
        isHuntingActive = false
        workerJob?.cancel()
    }

    /**
     * Kích hoạt quy trình săn mã tốc độ cao (svb-loop)
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true
        hasSelectedVoucher = false
        lastActionTime = 0L
        consecutiveOrderClicks = 0

        vibrateAlert(120)
        Log.d(TAG, "▶ START Săn Voucher: Code='$voucherCode', AutoOrder=$placeOrder")

        // Khởi chạy vòng lặp ngầm tần số cao (35-45ms) hệt như svb-loop trong APK gốc
        workerJob?.cancel()
        workerJob = serviceScope.launch {
            var loopCount = 0
            while (isActive && isHuntingActive) {
                loopCount++
                try {
                    executeStep()
                } catch (e: Throwable) {
                    Log.e(TAG, "Lỗi vòng lặp worker: ${e.message}")
                }
                delay(35L + Random.nextLong(0, 15)) // Nhịp 35-50ms cực nhạy
            }
        }
    }

    fun stopHunting() {
        isHuntingActive = false
        hasSelectedVoucher = false
        consecutiveOrderClicks = 0
        workerJob?.cancel()
        Log.d(TAG, "■ Đã dừng quy trình săn mã.")
    }

    /**
     * Máy trạng thái (State Machine) thực thi theo chuẩn của APK gốc
     */
    private fun executeStep() {
        val rootNode = rootInActiveWindow ?: return
        val currentPkg = rootNode.packageName?.toString() ?: ""

        // Chỉ thao tác khi app Shopee đang ở mặt tiền (Foreground)
        if (currentPkg != SHOPEE_PKG && !currentPkg.contains("shopee")) {
            return
        }

        val now = System.currentTimeMillis()

        // BƯỚC 1: XỬ LÝ POPUP LỖI SAU KHI ĐẶT HÀNG (Nếu có)
        if (dismissOrderPopup(rootNode)) {
            Log.d(TAG, "Đã phát hiện và đóng popup thông báo lỗi đơn hàng để tiếp tục!")
            return
        }

        val isCheckout = isCheckoutScreen(rootNode)
        val isVoucherScreen = isVoucherSelectionScreen(rootNode)

        // ================= BƯỚC 2: ĐANG Ở TRANG THANH TOÁN (CHECKOUT) =================
        if (isCheckout) {
            if (!hasSelectedVoucher) {
                // Chưa chọn voucher -> Bấm nút mở bảng Voucher (buttonCartPageUseVoucher)
                if (now - lastActionTime > 200) {
                    lastActionTime = now
                    val entryNode = findVoucherEntryNode(rootNode)
                    if (entryNode != null) {
                        Log.d(TAG, "▶ Đang ở Thanh toán -> Bấm nút [buttonCartPageUseVoucher] mở popup...")
                        clickOrTap(entryNode, "Vào mục Voucher")
                    }
                }
            } else {
                // Đã chọn voucher xong -> Bấm nút "Đặt hàng" (buttonPlaceOrder)
                if (autoPlaceOrder) {
                    val orderBtn = findOrderButtonNode(rootNode)
                    if (orderBtn != null) {
                        consecutiveOrderClicks++
                        if (consecutiveOrderClicks % 5 == 1) {
                            Log.d(TAG, "★ Bấm ĐẶT HÀNG (lần $consecutiveOrderClicks) qua buttonPlaceOrder!")
                        }
                        clickOrTap(orderBtn, "Đặt hàng")
                        vibrateAlert(70)
                    }
                }
            }
            return
        }

        // ================= BƯỚC 3: ĐANG Ở BẢNG CHỌN VOUCHER (POPUP / SHEET) =================
        if (isVoucherScreen) {
            // 3.1: Quét tìm Radio Button hoặc thẻ của Voucher mục tiêu
            val targetRadio = findTargetVoucherRadio(rootNode, targetVoucherCode)
            if (targetRadio != null) {
                Log.d(TAG, "★ ĐÃ TÌM THẤY VOUCHER mục tiêu -> Chọn ngay!")
                clickOrTap(targetRadio, "Chọn radio voucher")
                hasSelectedVoucher = true
                vibrateAlert(100)

                // 3.2: Bấm nút "Đồng ý" (btnOkVoucherSelectionSubmitSection)
                val submitBtn = findVoucherSubmitNode(rootNode)
                if (submitBtn != null) {
                    Log.d(TAG, "✔ Bấm nút [btnOkVoucherSelectionSubmitSection] (Đồng ý)!")
                    clickOrTap(submitBtn, "Đồng ý áp voucher")
                } else {
                    clickFirstMatchingText(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "Áp dụng"))
                }
                return
            }

            // 3.3: Nếu là mã ký tự tự do (VD: SVIP...), nhập vào ô EditText
            if (isAlphanumericCode(targetVoucherCode)) {
                val inputFilled = tryFillVoucherEditText(rootNode, targetVoucherCode)
                if (inputFilled) {
                    clickFirstMatchingText(rootNode, listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn"))
                    val submitBtn = findVoucherSubmitNode(rootNode)
                    if (submitBtn != null) {
                        clickOrTap(submitBtn, "Đồng ý")
                    } else {
                        clickFirstMatchingText(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận"))
                    }
                    hasSelectedVoucher = true
                    return
                }
            }

            // 3.4: NẾU CHƯA THẤY MÃ (Chưa đến giờ hoặc Shopee chưa cập nhật):
            // Thực hiện vòng lặp "Back -> Re-enter" để tải lại danh sách voucher tức thì!
            if (now - lastActionTime > 250) {
                lastActionTime = now
                Log.d(TAG, "◀ Chưa thấy mã trong popup -> Thoát ra để vào lại refresh...")
                val backBtn = findVoucherBackNode(rootNode)
                if (backBtn != null) {
                    clickOrTap(backBtn, "Đóng màn voucher")
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            return
        }

        // BƯỚC 4: DỰ PHÒNG CHỐT ĐƠN
        if (hasSelectedVoucher && autoPlaceOrder) {
            val orderBtn = findOrderButtonNode(rootNode)
            if (orderBtn != null) {
                clickOrTap(orderBtn, "Đặt hàng fallback")
            }
        }
    }

    /**
     * Nhận diện màn hình Thanh toán (Checkout) theo ID hoặc Text
     */
    private fun isCheckoutScreen(rootNode: AccessibilityNodeInfo): Boolean {
        // Ưu tiên 1: Có buttonPlaceOrder
        if (findLiveNodeByIdSuffix(rootNode, ID_PLACE_ORDER) != null) return true

        // Ưu tiên 2: Có text "Đặt hàng" / "ĐẶT HÀNG"
        val hasOrderText = rootNode.findAccessibilityNodeInfosByText("Đặt hàng").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐẶT HÀNG").isNotEmpty()
        val hasCheckoutTitle = rootNode.findAccessibilityNodeInfosByText("Thanh toán").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Tổng thanh toán").isNotEmpty()
        return hasOrderText || hasCheckoutTitle
    }

    /**
     * Nhận diện Bảng Chọn Voucher (Popup / Dialog)
     */
    private fun isVoucherSelectionScreen(rootNode: AccessibilityNodeInfo): Boolean {
        // Nếu có buttonPlaceOrder thì chắc chắn là trang Checkout, KHÔNG PHẢI voucher popup!
        if (findLiveNodeByIdSuffix(rootNode, ID_PLACE_ORDER) != null) return false

        // Có btnOkVoucherSelectionSubmitSection
        if (findLiveNodeByIdSuffix(rootNode, ID_VOUCHER_SUBMIT) != null) return true

        val hasConfirmBtn = rootNode.findAccessibilityNodeInfosByText("Đồng ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐỒNG Ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Xác nhận").isNotEmpty()
        val hasVoucherTitle = rootNode.findAccessibilityNodeInfosByText("Chọn Shopee Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Nhập mã Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Mã Giảm Giá").isNotEmpty()

        return hasConfirmBtn || hasVoucherTitle
    }

    /**
     * Tìm Node mở Voucher trên trang Thanh toán (ID: buttonCartPageUseVoucher)
     */
    private fun findVoucherEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findLiveNodeByIdSuffix(root, ID_VOUCHER_ENTRY)
        if (byId != null) return findClickableAncestor(byId) ?: byId

        val keywords = listOf(
            "Shopee Voucher",
            "Chọn hoặc nhập mã",
            "Mã giảm giá",
            "Miễn Phí Vận Chuyển",
            "Voucher của Shop"
        )
        for (kw in keywords) {
            val list = root.findAccessibilityNodeInfosByText(kw)
            for (node in list) {
                return findClickableAncestor(node) ?: node
            }
        }
        return null
    }

    /**
     * Tìm nút Đặt Hàng (ID: buttonPlaceOrder)
     */
    private fun findOrderButtonNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findLiveNodeByIdSuffix(root, ID_PLACE_ORDER)
        if (byId != null) return findClickableAncestor(byId) ?: byId

        val byText = root.findAccessibilityNodeInfosByText("Đặt hàng")
        for (n in byText) {
            val t = n.text?.toString()?.trim()
            if (t == "Đặt hàng" || t == "ĐẶT HÀNG") {
                return findClickableAncestor(n) ?: n
            }
        }
        return null
    }

    /**
     * Tìm nút Đồng ý nộp voucher (ID: btnOkVoucherSelectionSubmitSection)
     */
    private fun findVoucherSubmitNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findLiveNodeByIdSuffix(root, ID_VOUCHER_SUBMIT)
        if (byId != null) return findClickableAncestor(byId) ?: byId

        val list = root.findAccessibilityNodeInfosByText("Đồng ý")
        for (n in list) {
            val t = n.text?.toString()?.trim()
            if (t == "Đồng ý" || t == "ĐỒNG Ý") {
                return findClickableAncestor(n) ?: n
            }
        }
        return null
    }

    /**
     * Tìm nút Back/Đóng màn voucher (ID: buttonActionBarIcon)
     */
    private fun findVoucherBackNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findLiveNodeByIdSuffix(root, ID_ACTION_BAR_BACK)
        if (byId != null) return findClickableAncestor(byId) ?: byId

        val byText = root.findAccessibilityNodeInfosByText("Trở lại")
        for (n in byText) return findClickableAncestor(n) ?: n
        return null
    }

    /**
     * Quét tìm Radio Button voucher (ID: radioBtnVoucher_...)
     */
    private fun findTargetVoucherRadio(root: AccessibilityNodeInfo, targetCode: String): AccessibilityNodeInfo? {
        val radioNodes = mutableListOf<AccessibilityNodeInfo>()
        fun findRadios(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val id = node.viewIdResourceName
            if (id != null && id.contains(ID_VOUCHER_RADIO_PREFIX)) {
                radioNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                findRadios(node.getChild(i))
            }
        }
        findRadios(root)

        if (radioNodes.isNotEmpty()) {
            if (targetCode.isNotBlank()) {
                val clean = targetCode.trim().lowercase()
                for (r in radioNodes) {
                    val id = r.viewIdResourceName ?: ""
                    if (id.lowercase().contains(clean)) return r
                }
            }

            // Ưu tiên chọn radio chưa tick (_unselected)
            for (r in radioNodes) {
                val id = r.viewIdResourceName ?: ""
                if (id.endsWith("_unselected")) return r
            }
            return radioNodes.firstOrNull()
        }

        // Dự phòng tìm theo text nội dung thẻ voucher
        return findMatchingVoucherByText(root, targetCode)
    }

    /**
     * Đóng popup lỗi nếu Shopee hiện thông báo sau khi bấm Đặt hàng (dismissOrderPopup)
     */
    private fun dismissOrderPopup(root: AccessibilityNodeInfo): Boolean {
        // 1. Kiểm tra nút "Xem Voucher" (Khi áp voucher không thành công)
        val seeVoucherNodes = root.findAccessibilityNodeInfosByText("Xem Voucher")
        if (seeVoucherNodes.isNotEmpty()) {
            val node = seeVoucherNodes[0]
            val clickable = findClickableAncestor(node) ?: node
            clickOrTap(clickable, "Xem Voucher (Popup lỗi)")
            hasSelectedVoucher = false // Cho phép chọn lại
            return true
        }

        // 2. Kiểm tra nút buttonDefaultPositive (Đồng ý) trong popup Shopee
        val positiveBtn = findLiveNodeByIdSuffix(root, ID_ORDER_POPUP_POSITIVE)
        if (positiveBtn != null) {
            clickOrTap(positiveBtn, "Đồng ý (Popup lỗi)")
            return true
        }

        return false
    }

    /**
     * Bấm nhanh bằng performAction(ACTION_CLICK), nếu không ăn thì rơi về dispatchGesture vân tay
     */
    private fun clickOrTap(node: AccessibilityNodeInfo, label: String = ""): Boolean {
        // Thử thực hiện Action click gốc (độ trễ 0ms)
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performAction CLICK thành công trên node [$label]")
            return true
        }
        val clickableParent = findClickableAncestor(node)
        if (clickableParent != null && clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performAction CLICK thành công trên cha của [$label]")
            return true
        }

        // Bắn cử chỉ cảm ứng mô phỏng người thật nếu Shopee chặn Accessibility Click
        return performHumanLikeClick(node)
    }

    /**
     * Cử chỉ chạm mô phỏng người thật với micro-jitter
     */
    private fun performHumanLikeClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 5 && bounds.height() > 5) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()

            val safeRadiusX = width * 0.2f
            val safeRadiusY = height * 0.2f

            val randomOffsetX = (Random.nextFloat() * 2f - 1f) * safeRadiusX
            val randomOffsetY = (Random.nextFloat() * 2f - 1f) * safeRadiusY

            val startX = bounds.centerX() + randomOffsetX
            val startY = bounds.centerY() + randomOffsetY

            val driftX = startX + (Random.nextFloat() * 2f - 1f) * 1.5f
            val driftY = startY + (Random.nextFloat() * 2f - 1f) * 1.5f

            val contactDuration = Random.nextLong(35, 60)

            dispatchHumanTapGesture(startX, startY, driftX, driftY, contactDuration)
            return true
        }
        return false
    }

    private fun dispatchHumanTapGesture(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Tìm Node theo hậu tố ID (Suffix) trong cây giao diện
     */
    private fun findLiveNodeByIdSuffix(node: AccessibilityNodeInfo?, suffix: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val resName = node.viewIdResourceName
        if (resName != null && resName.endsWith(suffix)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findLiveNodeByIdSuffix(node.getChild(i), suffix)
            if (found != null) return found
        }
        return null
    }

    private fun findMatchingVoucherByText(rootNode: AccessibilityNodeInfo, targetCode: String): AccessibilityNodeInfo? {
        if (targetCode.isBlank()) return null
        val cleanTarget = targetCode.trim().lowercase()

        val directMatches = rootNode.findAccessibilityNodeInfosByText(targetCode)
        for (node in directMatches) {
            return findClickableAncestor(node) ?: node
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()?.lowercase() ?: ""
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (t.contains(cleanTarget) || d.contains(cleanTarget)) {
                allNodes.add(n)
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(rootNode)

        return allNodes.firstOrNull()?.let { findClickableAncestor(it) ?: it }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun clickFirstMatchingText(rootNode: AccessibilityNodeInfo, targetTexts: List<String>): Boolean {
        for (targetText in targetTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
            for (node in nodes) {
                if (clickOrTap(node, targetText)) return true
            }
        }
        return false
    }

    private fun isAlphanumericCode(code: String): Boolean {
        return code.length >= 6 && code.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun tryFillVoucherEditText(rootNode: AccessibilityNodeInfo, code: String): Boolean {
        val inputNodes = mutableListOf<AccessibilityNodeInfo>()
        fun findInputs(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.lowercase() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase() ?: ""
            } else ""

            if (node.isEditable || text.contains("nhập mã") || hint.contains("nhập mã") || hint.contains("voucher")) {
                inputNodes.add(node)
            }
            for (i in 0 until node.childCount) findInputs(node.getChild(i))
        }
        findInputs(rootNode)

        for (node in inputNodes) {
            if (node.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
        }
        return false
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
