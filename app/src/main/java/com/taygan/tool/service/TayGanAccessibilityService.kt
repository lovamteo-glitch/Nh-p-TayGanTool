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

        private val _currentStatusFlow = MutableStateFlow("Sẵn sàng")
        val currentStatusFlow = _currentStatusFlow.asStateFlow()

        private val _logFlow = MutableStateFlow(
            "12:01:14.735 CLICK node ← đóng màn voucher\n12:01:14.682 ◀ Không thấy voucher"
        )
        val logFlow = _logFlow.asStateFlow()

        fun isConnected(): Boolean = instance != null

        fun postStatus(status: String) {
            _currentStatusFlow.value = status
            postLog(status)
        }

        fun postLog(msg: String) {
            val df = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            val timestamp = df.format(java.util.Date())
            val newLine = "$timestamp $msg"
            Log.d(TAG, newLine)
            val current = _logFlow.value.lines().takeLast(6).toMutableList()
            current.add(newLine)
            _logFlow.value = current.joinToString("\n")
        }

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
    @Volatile private var voucherScreenEnteredTime = 0L
    @Volatile private var consecutiveOrderClicks = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceStatusFlow.value = true
        Log.d(TAG, "TayGanAccessibilityService connected - Accurate Shopee Loop Active!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHuntingActive || event == null) return
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
     * Kích hoạt quy trình săn mã
     */
    fun triggerHunting(voucherCode: String, placeOrder: Boolean) {
        targetVoucherCode = voucherCode
        autoPlaceOrder = placeOrder
        isHuntingActive = true
        hasSelectedVoucher = false
        lastActionTime = 0L
        voucherScreenEnteredTime = 0L
        consecutiveOrderClicks = 0

        vibrateAlert(120)
        postStatus("▶ Bắt đầu săn mã: $voucherCode")

        workerJob?.cancel()
        workerJob = serviceScope.launch {
            while (isActive && isHuntingActive) {
                try {
                    executeStep()
                } catch (e: Throwable) {
                    Log.e(TAG, "Lỗi vòng lặp worker: ${e.message}")
                }
                delay(35L + Random.nextLong(0, 15))
            }
        }
    }

    fun stopHunting() {
        isHuntingActive = false
        hasSelectedVoucher = false
        consecutiveOrderClicks = 0
        voucherScreenEnteredTime = 0L
        workerJob?.cancel()
        postStatus("■ Đã dừng quy trình săn mã")
    }

    /**
     * Máy trạng thái (State Machine) điều hướng cực kỳ chính xác
     */
    private fun executeStep() {
        val rootNode = rootInActiveWindow ?: return
        val currentPkg = rootNode.packageName?.toString() ?: ""

        if (currentPkg != SHOPEE_PKG && !currentPkg.contains("shopee")) {
            return
        }

        val now = System.currentTimeMillis()

        // BƯỚC 1: XỬ LÝ POPUP LỖI SAU KHI ĐẶT HÀNG (Nếu có)
        if (dismissOrderPopup(rootNode)) {
            postStatus("Đã đóng popup lỗi đơn hàng")
            return
        }

        val isCheckout = isCheckoutScreen(rootNode)
        val isVoucherScreen = isVoucherSelectionScreen(rootNode)

        // ================= BƯỚC 2: KHI ĐANG Ở TRANG THANH TOÁN (CHECKOUT) =================
        if (isCheckout) {
            voucherScreenEnteredTime = 0L // Reset thời gian màn voucher khi đã về Checkout

            if (!hasSelectedVoucher) {
                // TUYỆT ĐỐI KHÔNG BẤM "Đặt hàng" KHI CHƯA CHỌN ĐƯỢC VOUCHER MỤC TIÊU!
                if (now - lastActionTime > 300) {
                    lastActionTime = now
                    val entryNode = findVoucherEntryNode(rootNode)
                    if (entryNode != null) {
                        postStatus("Vào mục Shopee Voucher...")
                        clickOrTap(entryNode, "Vào mục Voucher")
                        voucherScreenEnteredTime = System.currentTimeMillis()
                    }
                }
            } else {
                // CHỈ BẤM ĐẶT HÀNG KHI ĐÃ ÁP VOUCHER THÀNH CÔNG!
                if (autoPlaceOrder) {
                    val orderBtn = findOrderButtonNode(rootNode)
                    if (orderBtn != null) {
                        consecutiveOrderClicks++
                        postStatus("★ Bấm ĐẶT HÀNG (lần $consecutiveOrderClicks)!")
                        clickOrTap(orderBtn, "Đặt hàng")
                        vibrateAlert(70)
                        if (consecutiveOrderClicks >= 2) {
                            stopHunting()
                        }
                    }
                }
            }
            return
        }

        // ================= BƯỚC 3: KHI ĐANG Ở BẢNG CHỌN VOUCHER (POPUP / SHEET) =================
        if (isVoucherScreen) {
            if (voucherScreenEnteredTime == 0L) {
                voucherScreenEnteredTime = now
            }

            // 3.1: Quét tìm ĐÚNG voucher mục tiêu
            val targetMatch = findTargetVoucherRadio(rootNode, targetVoucherCode)
            if (targetMatch != null) {
                val (radioNode, isAlreadySelected) = targetMatch
                if (isAlreadySelected) {
                    postStatus("★ Voucher đã được chọn sẵn!")
                } else {
                    postStatus("★ ĐÃ TÌM THẤY VOUCHER -> Chọn ngay!")
                    clickOrTap(radioNode, "Chọn radio voucher")
                }
                hasSelectedVoucher = true
                vibrateAlert(100)

                // Bấm nút "Đồng ý" (btnOkVoucherSelectionSubmitSection)
                val submitBtn = findVoucherSubmitNode(rootNode)
                if (submitBtn != null) {
                    postStatus("✔ Bấm Đồng ý áp voucher!")
                    clickOrTap(submitBtn, "Đồng ý áp voucher")
                } else {
                    clickFirstMatchingText(rootNode, listOf("Đồng ý", "ĐỒNG Ý", "Xác nhận", "Áp dụng"))
                }
                return
            }

            // 3.2: Nếu là mã dạng text tự nhập (VD: SVIPDD...), thử điền vào ô
            if (isAlphanumericCode(targetVoucherCode)) {
                val inputFilled = tryFillVoucherEditText(rootNode, targetVoucherCode)
                if (inputFilled) {
                    clickFirstMatchingText(rootNode, listOf("Áp dụng", "Áp Dụng", "ÁP DỤNG", "Chọn"))
                    val submitBtn = findVoucherSubmitNode(rootNode)
                    if (submitBtn != null) {
                        clickOrTap(submitBtn, "Đồng ý")
                    }
                    hasSelectedVoucher = true
                    return
                }
            }

            // 3.3: NẾU CHƯA THẤY VOUCHER MỤC TIÊU:
            // TUYỆT ĐỐI KHÔNG CHỌN BỪA VOUCHER KHÁC! TUYỆT ĐỐI KHÔNG BẤM ĐỒNG Ý!
            // Đợi Shopee nạp dữ liệu xong (khoảng 1.2s - 1.5s), nếu vẫn không có -> BACK ra Thanh toán và vào lại để REFRESH!
            val elapsed = now - voucherScreenEnteredTime
            if (elapsed > 1300) {
                lastActionTime = now
                postStatus("Chưa có mã → back ra, vào lại (${elapsed} ms)")
                val backBtn = findVoucherBackNode(rootNode)
                if (backBtn != null) {
                    clickOrTap(backBtn, "Đóng màn voucher")
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                voucherScreenEnteredTime = 0L
            } else {
                postStatus("Chưa có mã → back ra, vào lại (${elapsed} ms)")
            }
            return
        }
    }

    /**
     * Nhận diện màn hình Thanh toán (Checkout)
     */
    private fun isCheckoutScreen(rootNode: AccessibilityNodeInfo): Boolean {
        if (findLiveNodeByIdSuffix(rootNode, ID_PLACE_ORDER) != null) return true

        val hasOrderText = rootNode.findAccessibilityNodeInfosByText("Đặt hàng").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐẶT HÀNG").isNotEmpty()
        val hasCheckoutTitle = rootNode.findAccessibilityNodeInfosByText("Thanh toán").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Tổng thanh toán").isNotEmpty()
        return hasOrderText || hasCheckoutTitle
    }

    /**
     * Nhận diện Bảng Chọn Voucher
     */
    private fun isVoucherSelectionScreen(rootNode: AccessibilityNodeInfo): Boolean {
        if (findLiveNodeByIdSuffix(rootNode, ID_PLACE_ORDER) != null) return false
        if (findLiveNodeByIdSuffix(rootNode, ID_VOUCHER_SUBMIT) != null) return true

        val hasConfirmBtn = rootNode.findAccessibilityNodeInfosByText("Đồng ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("ĐỒNG Ý").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Xác nhận").isNotEmpty()
        val hasVoucherTitle = rootNode.findAccessibilityNodeInfosByText("Chọn Shopee Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Nhập mã Voucher").isNotEmpty() ||
                rootNode.findAccessibilityNodeInfosByText("Mã Giảm Giá").isNotEmpty()

        return hasConfirmBtn || hasVoucherTitle
    }

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

    private fun findVoucherBackNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findLiveNodeByIdSuffix(root, ID_ACTION_BAR_BACK)
        if (byId != null) return findClickableAncestor(byId) ?: byId

        val byText = root.findAccessibilityNodeInfosByText("Trở lại")
        for (n in byText) return findClickableAncestor(n) ?: n
        return null
    }

    /**
     * Quét tìm Radio Button hoặc thẻ của ĐÚNG voucher mục tiêu
     * Trả về Pair(Node, isAlreadySelected) hoặc null nếu KHÔNG tìm thấy
     */
    private fun findTargetVoucherRadio(root: AccessibilityNodeInfo, targetCode: String): Pair<AccessibilityNodeInfo, Boolean>? {
        if (targetCode.isBlank()) return null
        val cleanTarget = targetCode.trim().lowercase()
        val keywords = extractTargetKeywords(cleanTarget)

        val candidateRadios = mutableListOf<AccessibilityNodeInfo>()
        fun findRadios(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val id = node.viewIdResourceName ?: ""
            if (id.contains(ID_VOUCHER_RADIO_PREFIX)) {
                candidateRadios.add(node)
            }
            for (i in 0 until node.childCount) {
                findRadios(node.getChild(i))
            }
        }
        findRadios(root)

        if (candidateRadios.isNotEmpty()) {
            for (r in candidateRadios) {
                val id = (r.viewIdResourceName ?: "").lowercase()
                val isSelected = id.endsWith("_selected")

                // Khớp theo ID nếu cleanTarget là số hoặc mã
                if (id.contains(cleanTarget)) {
                    return Pair(r, isSelected)
                }

                // Kiểm tra nội dung thẻ cha (Card chứa radio này)
                var card: AccessibilityNodeInfo? = r.parent
                var depth = 0
                while (card != null && depth < 4) {
                    val cardText = collectAllText(card).lowercase()
                    if (keywords.isNotEmpty() && keywords.all { cardText.contains(it) }) {
                        return Pair(r, isSelected)
                    }
                    card = card.parent
                    depth++
                }
            }
        }

        // Tìm theo text trực tiếp trên màn hình
        val matchedTextNode = findNodeMatchingKeywords(root, keywords)
        if (matchedTextNode != null) {
            val radio = findRadioInSubtree(matchedTextNode)
            if (radio != null) {
                val isSelected = (radio.viewIdResourceName ?: "").endsWith("_selected")
                return Pair(radio, isSelected)
            }
            val clickable = findClickableAncestor(matchedTextNode) ?: matchedTextNode
            return Pair(clickable, false)
        }

        // TUYỆT ĐỐI KHÔNG CHỌN BỪA VOUCHER KHÁC NẾU KHÔNG TÌM THẤY!
        return null
    }

    private fun extractTargetKeywords(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val lower = text.lowercase()

        Regex("""\d+%""").findAll(lower).forEach { tokens.add(it.value) }
        Regex("""\d+k""").findAll(lower).forEach { tokens.add(it.value) }

        if (lower.contains("svip")) tokens.add("svip")
        if (lower.contains("xtra")) tokens.add("xtra")

        if (tokens.isEmpty()) {
            lower.split(" ", "_", "-")
                .map { it.trim() }
                .filter { it.length >= 3 && it != "giảm" && it != "tối" && it != "voucher" && it != "đơn" }
                .forEach { tokens.add(it) }
        }
        return tokens
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            sb.append(collectAllText(node.getChild(i)))
        }
        return sb.toString()
    }

    private fun findNodeMatchingKeywords(root: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (root == null || keywords.isEmpty()) return null
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()?.lowercase() ?: ""
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (keywords.all { t.contains(it) || d.contains(it) }) {
                allNodes.add(n)
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(root)
        return allNodes.firstOrNull()
    }

    private fun findRadioInSubtree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName ?: ""
        if (id.contains(ID_VOUCHER_RADIO_PREFIX)) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i)
                val cId = child?.viewIdResourceName ?: ""
                if (cId.contains(ID_VOUCHER_RADIO_PREFIX)) return child
            }
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun dismissOrderPopup(root: AccessibilityNodeInfo): Boolean {
        val seeVoucherNodes = root.findAccessibilityNodeInfosByText("Xem Voucher")
        if (seeVoucherNodes.isNotEmpty()) {
            val node = seeVoucherNodes[0]
            val clickable = findClickableAncestor(node) ?: node
            clickOrTap(clickable, "Xem Voucher (Popup lỗi)")
            hasSelectedVoucher = false
            return true
        }

        val positiveBtn = findLiveNodeByIdSuffix(root, ID_ORDER_POPUP_POSITIVE)
        if (positiveBtn != null) {
            clickOrTap(positiveBtn, "Đồng ý (Popup lỗi)")
            return true
        }
        return false
    }

    private fun clickOrTap(node: AccessibilityNodeInfo, label: String = ""): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performAction CLICK thành công trên node [$label]")
            return true
        }
        val clickableParent = findClickableAncestor(node)
        if (clickableParent != null && clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performAction CLICK thành công trên cha của [$label]")
            return true
        }
        return performHumanLikeClick(node)
    }

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
