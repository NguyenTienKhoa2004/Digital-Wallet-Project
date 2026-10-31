# FlowPay frontend

React + TypeScript + Vite + Ant Design. Giao diện tiếng Việt cho đăng ký, đăng nhập, tạo ví, xem số dư và nạp tiền; responsive cho desktop và điện thoại.

## Chạy local

Yêu cầu Node.js 22.12+ hoặc 24, npm và backend FlowPay đang chạy theo README ở thư mục gốc.

```sh
cd frontend
npm ci
npm run dev
```

Mở http://127.0.0.1:5173. Backend mặc định: http://127.0.0.1:8080. Nếu backend chạy cổng khác, sao chép `.env.example` thành `.env.local`, sửa `API_PROXY_TARGET`, rồi khởi động lại Vite.

Vite proxy chuyển `/api/*` sang `/*` của Spring Boot, nên không cần nới CORS trên backend. Không đặt `JWT_SECRET` hoặc bí mật server trong frontend.

Để nạp tiền tự hoàn tất, chạy backend với profile `mock-provider` và hai khóa dịch vụ theo [hướng dẫn top-up](../docs/top-up-api.md). Khi chưa bật worker/provider, yêu cầu vẫn có thể được tạo nhưng sẽ giữ trạng thái `PENDING`. Đây là môi trường mô phỏng, không sử dụng tiền thật; giao diện không cần và không giữ khóa provider/callback.

## Hành vi

- `/register`: tên hiển thị, email, mật khẩu và xác nhận. Gọi API đăng ký, sau đó chuyển sang `/login` với email đã điền và thông báo thành công. Không tự động đăng nhập.
- `/login`: gọi API đăng nhập, nhận access token và thông tin người dùng, chuyển sang `/account`.
- `/account`: trang ví sau đăng nhập, có tạo ví USD, số dư và trạng thái ví, form nạp tiền, lịch sử phân trang và chi tiết từng lần nạp. Trang chủ gồm lời chào, số dư có ẩn/hiện, thao tác nhanh, dịch vụ, giao dịch gần đây, ưu đãi và thanh điều hướng dưới. Nạp tiền mở trong drawer; Cá nhân hiển thị tài khoản và đăng xuất; Thống kê tổng hợp tối đa 5 giao dịch trên trang lịch sử hiện tại. Chuyển tiền, quét QR, hóa đơn, điện thoại và ngân hàng hiển thị thông báo đang phát triển. Nhận tiền hiện mã ví và nút sao chép, chưa thực hiện giao dịch. Chưa có chương trình ưu đãi thực tế.
- Nút mặt trời/mặt trăng ở đầu trang chuyển theme sáng/tối cho toàn bộ giao diện và component Ant Design. Mặc định dùng theme tối xanh dương theo thiết kế Glassmorphism; lựa chọn thủ công lưu trong localStorage (`flowpay:theme`), áp dụng ngay trước khi trang hiển thị và đồng bộ giữa các tab.
- Access token chỉ nằm trong bộ nhớ, không lưu localStorage/sessionStorage. Đăng xuất, tải lại trang hoặc hết hạn token cần đăng nhập lại. Backend chưa cung cấp refresh token; chưa có khôi phục mật khẩu hoặc đăng nhập mạng xã hội.
- Mật khẩu giữ nguyên giá trị, kiểm tra tối thiểu 8 ký tự khi đăng ký và giới hạn 72 byte UTF-8 khớp với BCrypt ở backend.
- Đã xử lý email trùng, sai thông tin đăng nhập, lỗi kết nối, lỗi máy chủ và chặn gửi lặp trong lúc chờ.
- Số tiền nhập theo USD với tối đa hai chữ số thập phân (dấu chấm hoặc phẩy), chuyển chính xác thành integer cents cho backend. Giá trị tiền từ Java `long` được đọc thành chuỗi và định dạng bằng BigInt để tránh mất độ chính xác.
- `POST /wallets` tạo ví; `GET /wallets/me` lấy số dư thật. Khi lỗi kết nối, tải lại ví để xác định kết quả; `WALLET_ALREADY_EXISTS` sẽ tải ví hiện có.
- Mỗi yêu cầu nạp dùng một UUID `Idempotency-Key`. Trước khi POST, lưu key và payload vào sessionStorage theo `userId` để khôi phục sau khi tải lại trang/đăng nhập lại trong cùng tab. Không lưu token hoặc mật khẩu. Chỉ theo dõi một yêu cầu nạp mới tại một thời điểm; khi chưa rõ kết quả, giữ nguyên key và payload nếu gửi lại.
- GET theo key/reference và lịch sử được cập nhật mỗi 5 giây khi có yêu cầu đang chờ. `PENDING`, timeout và `OPERATION_NOT_FOUND` không được coi là thất bại; chỉ hiển thị thành công/thất bại khi backend xác nhận. Khi hoàn tất, tải lại số dư và lịch sử. Không tự POST lại qua polling.
- Ví khóa không thể tạo yêu cầu nạp mới. HTTP 401 đưa người dùng về đăng nhập và giữ thông tin khôi phục yêu cầu nạp.

## Build

```sh
npm run build
```

## Khi triển khai

Build tạo thư mục `dist/`. Cấu hình web server trả `index.html` cho các đường dẫn frontend (`/login`, `/register`, `/account`), và reverse proxy `/api/*` tới Spring Boot, bỏ tiền tố `/api`. Vite development proxy không có trong bản build. Dùng HTTPS khi triển khai. Font Be Vietnam Pro tải từ Google Fonts; nếu không có mạng, giao diện dùng font hệ thống.
