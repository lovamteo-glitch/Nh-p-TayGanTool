package com.taygan.tool.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.taygan.tool.model.BadgeType
import com.taygan.tool.model.VoucherItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class SaveApiResult {
    data class Success(val message: String, val alreadyClaimed: Boolean) : SaveApiResult()
    data class Retry(val message: String) : SaveApiResult()
    data class Failed(val message: String, val isTerminal: Boolean) : SaveApiResult()
}

class ShopeeApiService(private val context: Context) {

    private val sessionManager = ShopeeSessionManager.getInstance(context)
    private val gson = Gson()
    private val tag = "ShopeeApiService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        const val REMOTE_CONFIG_URL = "https://sansalelinhkienpc.com/api/v1/voucher-config"
        const val SAVE_VOUCHER_URL = "https://shopee.vn/api/v4/microsite/save_voucher"
        const val VIP_COLLECTIONS_URL = "https://shopee.vn/api/v1/microsite/get_vouchers_by_collections"
    }

    /**
     * Bắn trực tiếp request API lưu voucher vào kho (Theo đúng chuẩn dòng 3423-3533 của NKQLuuVoucherV6Fix.js)
     */
    suspend fun saveVoucherDirect(voucher: VoucherItem): SaveApiResult = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn) {
            return@withContext SaveApiResult.Failed(
                "Chưa đăng nhập Shopee. Vui lòng nhập Cookie hoặc đăng nhập trong Cài đặt!",
                isTerminal = true
            )
        }

        if (voucher.promotionId <= 0 || voucher.signature.isBlank()) {
            return@withContext SaveApiResult.Failed(
                "Voucher ${voucher.code} thiếu promotionId hoặc signature. Cần cập nhật từ server cấu hình!",
                isTerminal = true
            )
        }

        val jsonBody = JsonObject().apply {
            addProperty("voucher_code", voucher.code)
            addProperty("voucher_promotionid", voucher.promotionId)
            addProperty("signature", voucher.signature)
            addProperty("signature_source", voucher.signatureSource)
            addProperty("caller_source", voucher.callerSource)
            addProperty("security_device_fingerprint", sessionManager.fingerprint)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(SAVE_VOUCHER_URL)
            .post(requestBody)
            .addHeader("accept", "application/json")
            .addHeader("content-type", "application/json")
            .addHeader("cache-control", "no-cache")
            .addHeader("pragma", "no-cache")
            .addHeader("x-api-source", "pc")
            .addHeader("x-requested-with", "XMLHttpRequest")
            .addHeader("x-csrftoken", sessionManager.csrfToken)
            .addHeader("Cookie", sessionManager.cookie)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        try {
            val response = client.newCall(request).execute()
            val statusCode = response.code
            val bodyString = response.body?.string() ?: ""

            Log.d(tag, "Save voucher ${voucher.code} response ($statusCode): $bodyString")

            if (statusCode == 403 || statusCode == 418) {
                return@withContext SaveApiResult.Failed("Shopee yêu cầu xác minh bảo mật (HTTP $statusCode)", isTerminal = true)
            }
            if (statusCode == 429) {
                return@withContext SaveApiResult.Retry("Shopee đang giới hạn yêu cầu (Rate Limit)")
            }
            if (statusCode >= 500) {
                return@withContext SaveApiResult.Retry("Server Shopee đang tạm thời quá tải (HTTP $statusCode)")
            }

            val json = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                return@withContext SaveApiResult.Retry("Phản hồi JSON không hợp lệ, tiếp tục thử lại")
            }

            val errorCode = json.get("error")?.asInt ?: -1
            if (errorCode == 0) {
                val dataObj = json.getAsJsonObject("data")
                val voucherObj = dataObj?.getAsJsonObject("voucher")
                val isClaimedBefore = voucherObj?.get("is_claimed_before")?.asBoolean ?: false
                return@withContext SaveApiResult.Success("Lưu voucher thành công!", alreadyClaimed = isClaimedBefore)
            }

            val rawMsg = bodyString.lowercase()
            if (rawMsg.contains("ban da luu voucher") || rawMsg.contains("voucher da co trong kho") || rawMsg.contains("voucher nay da duoc luu")) {
                return@withContext SaveApiResult.Success("Voucher đã có sẵn trong kho ví!", alreadyClaimed = true)
            }

            if (rawMsg.contains("chua den gio") || rawMsg.contains("chua bat dau") || rawMsg.contains("not started")) {
                return@withContext SaveApiResult.Retry("Voucher chưa đến giờ mở lưu • đang chờ...")
            }

            if (rawMsg.contains("het luot") || rawMsg.contains("fully redeemed") || json.get("data")?.asJsonObject?.get("invalid_message_code")?.asInt == 4) {
                return@withContext SaveApiResult.Retry("Voucher tạm hết lượt • đang chờ back lượt...")
            }

            val errorMsg = json.get("error_msg")?.asString ?: json.get("message")?.asString ?: "Không thể lưu voucher"
            return@withContext SaveApiResult.Failed("Lỗi: $errorMsg", isTerminal = false)

        } catch (e: Exception) {
            Log.e(tag, "Lỗi mạng khi lưu voucher: ${e.message}")
            return@withContext SaveApiResult.Retry("Lỗi mạng tạm thời, đang gửi lại...")
        }
    }

    /**
     * Tải danh sách cấu hình voucher từ Server trung gian (sansalelinhkienpc.com)
     */
    suspend fun fetchRemoteVoucherConfig(): Result<List<VoucherItem>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$REMOTE_CONFIG_URL?t=${System.currentTimeMillis()}")
                .get()
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error: ${response.code}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Phản hồi rỗng"))
            val json = gson.fromJson(body, JsonObject::class.java)

            val rawVouchers = json.getAsJsonArray("vouchers") ?: return@withContext Result.failure(Exception("Không có danh sách vouchers"))
            val resultList = mutableListOf<VoucherItem>()

            for (element in rawVouchers) {
                val obj = element.asJsonObject
                val code = obj.get("voucher_code")?.asString ?: continue
                val promoId = obj.get("voucher_promotionid")?.asLong ?: 0L
                val signature = obj.get("signature")?.asString ?: ""
                val label = obj.get("label")?.asString ?: code

                resultList.add(
                    VoucherItem(
                        id = code,
                        code = code,
                        label = label,
                        badgeType = if (code.startsWith("SVIP")) BadgeType.VIP else BadgeType.CUSTOM,
                        promotionId = promoId,
                        signature = signature,
                        signatureSource = obj.get("signature_source")?.asString ?: "0",
                        callerSource = obj.get("caller_source")?.asInt ?: 6
                    )
                )
            }

            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
