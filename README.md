# FlowPay Backend

Backend cho ví điện tử FlowPay. Đã có đăng ký/đăng nhập JWT, API tạo ví cùng ledger account, PostgreSQL, Flyway và health endpoint. Chưa triển khai chuyển tiền, nạp tiền hoặc đối soát.

Xem [Tổng quan kỹ thuật](FlowPayTechnicalOverview.md) để hiểu cấu trúc thư mục, kiến trúc đề xuất, luồng khởi động và cách làm việc với database.

## Stack

- Java 21, Spring Boot 4.1.1, Maven Wrapper.
- Spring MVC, Bean Validation, Spring Data JPA / Hibernate.
- Spring Security, JWT Bearer token (HS256), mật khẩu băm BCrypt.
- PostgreSQL 17, Flyway.
- Spring Boot Actuator.
- JUnit, AssertJ, Testcontainers.

## Điều kiện chạy

- JDK 21: `java -version` và `javac -version`.
- Docker Desktop đang chạy: `docker version` có cả Client và Server.
- Có kết nối Internet cho lần tải Maven, dependencies và Docker image đầu tiên.
- Cổng 5432 và 8080 đang trống, hoặc đổi cấu hình như bên dưới.

Không cần cài Maven hay PostgreSQL trực tiếp trên máy.

## Chạy local

Tại thư mục project:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
docker compose up -d --wait postgres
./mvnw spring-boot:run
```

`JWT_SECRET` là khóa ký dạng Base64, giải mã thành ít nhất 32 byte ngẫu nhiên. Tạo một lần và giữ ổn định trong môi trường chạy; đổi khóa sẽ làm token cũ không còn hợp lệ. Không commit khóa vào source code. Không còn profile hoặc tài khoản demo tự tạo.

Giữ terminal chạy ứng dụng; mở terminal khác để kiểm tra:

```bash
curl http://localhost:8080/actuator/health
```

Kết quả có `status` là `UP` (có thể kèm các trường khác):

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

Ứng dụng cần PostgreSQL đang chạy. Flyway tự áp dụng migration trước khi Hibernate khởi tạo; `ddl-auto: validate` không tự tạo/sửa bảng từ entity.

## Database local

| Thuộc tính | Mặc định |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `flowpay` |
| Username | `flowpay` |
| Password | `flowpay_local` |
| Schema nghiệp vụ | `flowpay` |
| Flyway history | `public.flyway_schema_history` |

Đây là credential dành cho môi trường local. Compose chỉ publish PostgreSQL trên `127.0.0.1`.

Mở PostgreSQL CLI trong container:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Migration `V1` tạo schema `flowpay`; `V2__create_business_tables.sql` tạo 7 bảng P0 và bảng lịch sử đối soát P1 theo [ERD](FlowPayERD.md), cùng FK, UNIQUE, CHECK và index. `V3__add_user_password_hash.sql` bổ sung mật khẩu đã băm. User cũ không có password hash không thể đăng nhập; không gán mật khẩu mặc định cho các user này. Không sửa migration đã áp dụng; tạo phiên bản mới.

Các cột `updated_at` phải được application truyền khi insert và cập nhật khi sửa dữ liệu. Các quy tắc liên bảng (cân bằng ledger, posting một lần, trạng thái cuối) cần service transaction và khóa phù hợp. Chưa cấu hình role database riêng để cấm UPDATE/DELETE ledger; user local của Compose không phải role giới hạn quyền cho production.

## Test và build

Chạy unit test (không cần Docker):

```bash
./mvnw test
```

Chạy unit test, integration test và đóng gói:

```bash
./mvnw verify
```

Unit test đặt trong `unit/`, có tên `*Test.java` và chạy bằng Maven Surefire. Integration test đặt trong `integration/`, có tên `*IT.java` và chạy bằng Maven Failsafe ở bước `integration-test`/`verify`. Cấu hình và launcher dùng chung đặt trong `support/`.

Integration test dùng Testcontainers tự tạo PostgreSQL riêng, chạy migration và kiểm tra API qua HTTP. Không dùng hoặc sửa dữ liệu database local của Compose. Docker Desktop phải đang chạy khi chạy integration test, kể cả khi không khởi động Compose.

Chỉ chạy integration test:

```bash
./mvnw test-compile failsafe:integration-test failsafe:verify
```

Nếu Testcontainers không tìm được Docker trên macOS, chạy:

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./mvnw verify
```

Sau build, có thể chạy file JAR thay cho Maven:

```bash
java -jar target/flowpay-0.0.1-SNAPSHOT.jar
```

## Cấu hình qua environment variables

| Biến | Mặc định | Dùng bởi |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/flowpay` | Spring Boot |
| `DB_USERNAME` | `flowpay` | Spring Boot và Compose |
| `DB_PASSWORD` | `flowpay_local` | Spring Boot và Compose |
| `DB_PORT` | `5432` | Compose, cổng host |
| `SERVER_PORT` | `8080` | Spring Boot |
| `JWT_SECRET` | Bắt buộc, không có mặc định | Khóa ký/xác minh JWT, Base64 của ít nhất 32 byte |
| `JWT_ISSUER` | `flowpay` | Issuer của JWT |
| `JWT_AUDIENCE` | `flowpay-api` | Audience của JWT |
| `JWT_ACCESS_TOKEN_TTL_SECONDS` | `900` | Thời hạn access token theo giây, cho phép 60–86400 |

Nếu cổng 5432 đã được dùng:

```bash
DB_PORT=5433 docker compose up -d --wait postgres
DB_URL=jdbc:postgresql://localhost:5433/flowpay ./mvnw spring-boot:run
```

Spring Boot không tự đọc file `.env`; nếu thay credential cho Compose bằng file `.env`, cần export cùng các biến cho tiến trình Java. Credential PostgreSQL chỉ được khởi tạo khi volume còn trống; thay biến môi trường không tự đổi password trong volume đã có dữ liệu.

## Dừng

- Ứng dụng: `Ctrl+C` tại terminal đang chạy.
- Database: `docker compose down` — giữ dữ liệu trong named volume.

## Cấu trúc

```text
src/main/java/com/flowpay/          # Application và các module nghiệp vụ về sau
src/main/resources/application.yaml
src/main/resources/db/migration/  # Flyway SQL migrations
src/test/java/com/flowpay/unit/        # Unit tests (*Test.java), chia theo module
src/test/java/com/flowpay/integration/ # Integration tests (*IT.java), chia theo module
src/test/java/com/flowpay/support/     # PostgreSQL Testcontainer và launcher cho test
compose.yaml                      # PostgreSQL local
pom.xml                           # Dependencies và build
mvnw                              # Maven Wrapper cho macOS/Linux
FlowPayPRD.md                      # Yêu cầu nghiệp vụ
FlowPayERD.md                      # Thiết kế dữ liệu
```

## Đăng ký, đăng nhập và tạo ví

| Method | Endpoint | Kết quả |
|---|---|---|
| `POST` | `/auth/register` | `201`: tạo user, chưa tự tạo ví |
| `POST` | `/auth/login` | `200`: trả `accessToken`, `tokenType`, `expiresIn` và thông tin user |
| `POST` | `/wallets` | `201`: tạo ví 0 USD, ACTIVE cùng ledger account cho user trong JWT |

Body đăng ký:

```json
{
  "username": "Johnny",
  "email": "johnny@example.com",
  "password": "choose-a-strong-password"
}
```

Body đăng nhập:

```json
{
  "email": "johnny@example.com",
  "password": "choose-a-strong-password"
}
```

Sau đăng nhập, gửi header `Authorization: Bearer <accessToken>` khi gọi `POST /wallets` (không cần body). Backend lấy user ID từ claim `sub` của token đã xác minh. HTTP Basic đã bị bỏ.

- Email được chuẩn hóa chữ thường, đăng ký trùng trả `409 EMAIL_ALREADY_EXISTS`.
- Password đăng ký tối thiểu 8 ký tự và tối đa 72 byte UTF-8; chỉ lưu hash BCrypt, không trả password/hash trong response.
- User mới luôn có role `USER`; client không được tự chọn role `ADMIN`.
- Đăng nhập sai trả `401 INVALID_CREDENTIALS`; token thiếu/sai/hết hạn trả `401`; không đủ quyền trả `403`.
- JWT kiểm tra chữ ký HS256, issuer, audience, thời hạn và UUID subject. Access token mặc định có thời hạn 15 phút; bộ kiểm tra thời gian của Spring Security có clock skew mặc định.
- Hiện chỉ có access token, chưa có refresh token hoặc cơ chế thu hồi từng token. Khi hết hạn cần đăng nhập lại. Dùng HTTPS khi triển khai.

Tổ chức code: `auth/` xử lý đăng ký/đăng nhập và cấp token; `user/` lưu user; `wallet/` xử lý ví; `ledger/` tạo account; `common/config/` cấu hình JWT và bảo mật; `common/exception/` trả lỗi API.
