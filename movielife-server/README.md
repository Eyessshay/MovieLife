# MovieLife Server

Java 8 / Spring Boot 2.7 backend for MovieLife.

## Local prerequisites

- MySQL 8.0 with `../sql/movielife.sql` imported
- Redis listening on port 6379
- JDK 8

The application reads credentials from environment variables and does not store passwords in source control:

```powershell
$env:MYSQL_USERNAME = 'root'
$env:MYSQL_PASSWORD = '你的数据库密码'
$env:AUTH_LOG_CODE_ENABLED = 'true' # 仅本地开发需要查看模拟验证码时开启
$env:REDIS_PASSWORD = ''
.\mvnw.cmd spring-boot:run
```

On startup, the service queries movie ID 1 and sends Redis `PING`. The application only completes startup when both checks pass. Set `STARTUP_CHECK_ENABLED=false` only for compilation or isolated development that does not require infrastructure.
