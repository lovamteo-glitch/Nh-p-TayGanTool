# Tay Gân Tool (Bản Tích Hợp API Lưu Mã Shopee & Auto Click Đặt Hàng)

Ứng dụng Android APK chuyên nghiệp kết hợp **2 công nghệ săn sale hàng đầu**:
1. **Lưu mã qua API trực tiếp (Kế thừa từ `NKQLuuVoucherV6Fix.js`)**: Bắn request ngầm trực tiếp vào hệ thống Shopee để đưa voucher vào kho ví ngay mili-giây mở đợt sale.
2. **Auto Click Trợ năng (Tay Gân Tool)**: Tự động điền mã, áp voucher và spam-click nút "Đặt hàng" / "Thanh toán" nhanh hơn thao tác tay 50 lần.

---

## 🌟 Các Tính Năng Đã Tích Hợp Hoàn Chỉnh

1. **Bảng điều khiển nổi (Floating Overlay Window)**:
   - Chạy đè trên mọi ứng dụng (đặc biệt là Shopee), hỗ trợ kéo thả (`Drag & Drop`).
   - Nút thu nhỏ (`^`) thành bong bóng nổi mini (pill `vcl` / đồng hồ nổi) để không che khuất màn hình khi lướt giỏ hàng.
2. **Hệ thống Tab kép**:
   - **Săn mã**: Danh sách voucher và cài đặt auto săn.
   - **Deal SVIP / API Hunter**: Tích hợp các mã SVIP có sẵn Promotion ID và Signature để lưu qua API.
3. **Bộ xử lý API Lưu Mã Trực Tiếp (Shopee API Engine)**:
   - Endpoint: `POST https://shopee.vn/api/v4/microsite/save_voucher`
   - Quản lý Cookie & `security_device_fingerprint` chuẩn xác.
   - Hỗ trợ tải cấu hình mã tự động từ máy chủ `sansalelinhkienpc.com/api/v1/voucher-config`.
   - Có WebView tích hợp sẵn để đăng nhập Shopee và tự động trích xuất Cookie trong 1 click.
4. **Combo Săn Sale Độc Quyền (API + Auto Click)**:
   - Khi bấm **Bắt đầu**:
     - *Bước 1*: Bắn API lưu voucher vào kho tài khoản trong tích tắc.
     - *Bước 2*: Lập tức điều khiển Accessibility Service chọn mã và spam-click nút **"Đặt hàng"** để chốt đơn thành công!
5. **Đồng hồ mili-giây chuẩn xác cao**:
   - Hiển thị thời gian thực đến phần mười giây / mili-giây (`22:11:43.3`).

---

## 🚀 Hướng Dẫn Build File APK

### Cách 1: Tự động Build bằng GitHub Actions (Khuyên dùng - 2 phút có file APK)
File cấu hình CI/CD đã có sẵn tại `.github/workflows/build-apk.yml`.
1. Đẩy thư mục này lên GitHub cá nhân của bạn.
2. GitHub sẽ tự động biên dịch và tạo file APK.
3. Vào tab **Actions** trên GitHub để tải file **`TayGanTool-v1.0-debug.apk`** về điện thoại.

### Cách 2: Biên dịch bằng Android Studio
1. Mở Android Studio ➔ Chọn thư mục `TayGanTool`.
2. Menu: **Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**.
3. File APK xuất ra tại: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📱 Hướng Dẫn Sử Dụng Tính Năng Lưu Mã API Trên Điện Thoại

1. Cài đặt file APK lên điện thoại.
2. Bật quyền **Cửa sổ nổi** và **Trợ năng (Accessibility)**.
3. Tại màn hình chính của App:
   - Bấm **"Đăng nhập Shopee"**: Trình duyệt WebView sẽ mở ra, bạn đăng nhập tài khoản Shopee của mình ➔ Bấm **"LƯU TỰ ĐỘNG"** để app bắt lấy Cookie và Token CSRF.
   - Bấm **"Cập nhật mã từ Server (Kèm Signature API)"**: App sẽ tự động tải các mã giảm giá SVIP mới nhất có chữ ký API.
4. Bấm **"Mở bảng điều khiển nổi"**.
5. Mở ứng dụng **Shopee**, chọn sẵn sản phẩm vào trang Thanh toán.
6. Trên bảng nổi Tay Gân Tool:
   - Chọn voucher (các voucher có huy hiệu `API` sẽ được tự động lưu ngầm).
   - Bật **Tự đặt hàng sau khi áp voucher**.
   - Bật **Săn theo mốc thời gian** (ví dụ: `23 : 59 : 58`).
7. Bấm ▶ **Bắt đầu**!
