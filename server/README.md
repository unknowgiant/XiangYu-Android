# 乡遇同步服务

这是乡遇账号、设备会话和个人数据同步的 Spring Boot 单体服务。服务只监听
`127.0.0.1:8080`，由 Nginx 在配置正式域名和 TLS 后反向代理。

## 本地构建

从仓库根目录运行：

```bash
./gradlew -p server clean test bootJar
```

生产环境必须通过环境文件提供以下配置，不能提交到 Git：

- `XIANGYU_DB_URL`
- `XIANGYU_DB_USER`
- `XIANGYU_DB_PASSWORD`
- `XIANGYU_PHONE_ENCRYPTION_KEY`
- `XIANGYU_PHONE_HMAC_KEY`
- `XIANGYU_CURSOR_HMAC_KEY`
- `XIANGYU_SMS_MODE`

三个密钥均为独立的 32 字节随机值，以标准 Base64 编码。短信未完成供应商配置时，
`XIANGYU_SMS_MODE=disabled`；此时发送验证码接口返回 `SMS_NOT_CONFIGURED`，不会使用固定
验证码，也不会把验证码写入日志。

## 安全边界

- 访问令牌和刷新令牌均为高熵随机值，数据库只保存 SHA-256 哈希。
- 刷新令牌每次使用后轮换。
- 手机号使用 AES-256-GCM 加密保存，唯一查询使用独立 HMAC。
- 同步写入使用实体版本和 mutation UUID，防止弱网重试重复写入。
- 笔记和日志正文不写入应用日志。
- PostgreSQL 不对公网开放，服务本身也不监听公网地址。
