package com.taygan.tool.model

enum class BadgeType {
    VIP,
    INSTAGRAM,
    FACEBOOK,
    CUSTOM
}

data class VoucherItem(
    val id: String = System.currentTimeMillis().toString(),
    var code: String,
    var badgeType: BadgeType = BadgeType.VIP,
    var isSelected: Boolean = false,
    var discountPercent: Int = 0,
    var label: String = code,

    // API Claiming parameters (từ NKQLuuVoucherV6Fix.js)
    var promotionId: Long = 0L,
    var signature: String = "",
    var signatureSource: String = "0",
    var callerSource: Int = 6,
    var backHours: List<Int> = emptyList()
) {
    val isApiClaimable: Boolean
        get() = promotionId > 0L && signature.isNotBlank()

    companion object {
        fun getDefaultList(): MutableList<VoucherItem> {
            return mutableListOf(
                VoucherItem(
                    id = "1",
                    code = "SVIPDDVKH0711M0C500",
                    badgeType = BadgeType.VIP,
                    isSelected = true,
                    discountPercent = 50,
                    label = "SVIPDDVKH0711M0C500 (Giảm 50% max 500k)",
                    promotionId = 43824188L,
                    signature = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    signatureSource = "0",
                    callerSource = 6
                ),
                VoucherItem(
                    id = "2",
                    code = "Giảm 35% Tối đa 150k",
                    badgeType = BadgeType.VIP,
                    isSelected = false,
                    discountPercent = 35,
                    label = "Giảm 35% Tối đa 150k"
                ),
                VoucherItem(
                    id = "3",
                    code = "Giảm 50% tối đa 50k",
                    badgeType = BadgeType.INSTAGRAM,
                    isSelected = false,
                    discountPercent = 50,
                    label = "Giảm 50% tối đa 50k"
                ),
                VoucherItem(
                    id = "4",
                    code = "Giảm 30% tối đa 50k",
                    badgeType = BadgeType.FACEBOOK,
                    isSelected = false,
                    discountPercent = 30,
                    label = "Giảm 30% tối đa 50k"
                ),
                VoucherItem(
                    id = "5",
                    code = "Giảm 30% Giảm đối đa 500k",
                    badgeType = BadgeType.VIP,
                    isSelected = false,
                    discountPercent = 30,
                    label = "Giảm 30% Giảm đối đa 500k"
                ),
                VoucherItem(
                    id = "6",
                    code = "Giảm 50% Giảm tối đa 500k",
                    badgeType = BadgeType.VIP,
                    isSelected = false,
                    discountPercent = 50,
                    label = "Giảm 50% Giảm tối đa 500k"
                )
            )
        }
    }
}
