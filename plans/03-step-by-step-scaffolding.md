# Pet-care-new: Step-by-Step Scaffolding Guide

> **Date:** 2026-08-18  
> **Duration:** 6 tuần  
> **Scope:** 22 modules, 6 FSMs  
> **Purpose:** Hướng dẫn tạo project Java Spring Boot từ đầu

---

## 1. Roles (8 Roles)

| Scope | Role | Mô tả | Ai |
|-------|------|-------|-----|
| Platform | SUPER_ADMIN | Quản trị toàn hệ thống, setup | Dev / PO |
| Organization | ORG_ADMIN | Quản lý chuỗi cửa hàng | Chủ chuỗi |
| Store | STORE_MANAGER | Quản lý store, duyệt refunds | Chủ store |
| Store | FINANCE_STAFF | Thu ngân, đối soát | Kế toán |
| Store | INVENTORY_STAFF | Quản lý kho | Thủ kho |
| Store | RECEPTIONIST | Tiếp khách, check-in, tạo đơn | Lễ tân |
| Store | VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng | Bác sĩ |
| Store | GROOMER | Làm đẹp thú cưng | Stylist |
| User | CUSTOMER | Đặt lịch, mua hàng, thanh toán | Khách hàng |

---

## 2. Prerequisites

```bash
# Kiểm tra versions
java -version        # Cần Java 21+
mvn -version         # Cần Maven 3.9+
docker --version     # Cần Docker (cho PostgreSQL, Redis)
```

---

## 3. Step 1: Tạo Project Structure

```bash
# Tạo folder structure
cd D:/Do-an/Pet-care-new
mkdir -p BE
cd BE

# Tạo Maven project
mvn archetype:generate -DgroupId=com.petcare -DartifactId=petcare-api \
    -DarchetypeArtifactId=maven-archetype-quickstart -DinteractiveMode=false

# Hoặc copy từ base nếu có
# cp -r D:/Do-an/Pet-care/BE/services/auth-service D:/Do-an/Pet-care-new/BE/
```

---

## 4. Step 2: Tạo pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.15</version>
    </parent>

    <groupId>com.petcare</groupId>
    <artifactId>petcare-api</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <jjwt.version>0.12.3</jjwt.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- JWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 5. Step 3: Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  postgres:
    image: postgres:16
    container_name: petcare-postgres
    environment:
      POSTGRES_DB: petcare
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7
    container_name: petcare-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

volumes:
  postgres_data:
  redis_data:
```

```bash
# Chạy Docker Compose
docker-compose up -d

# Kiểm tra
docker ps
```

---

## 6. Step 4: Application Config

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: petcare-api

  datasource:
    url: jdbc:postgresql://localhost:5432/petcare
    username: postgres
    password: postgres

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: ${JWT_SECRET:your-256-bit-secret-key-here-min-32-characters-long}
  access-token-ttl-min: 60
  refresh-token-ttl-days: 30

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

---

## 7. Step 5: Project Structure

```
src/main/java/com/petcare/
├── PetcareApplication.java
│
├── common/
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── RedisConfig.java
│   │   └── WebConfig.java
│   ├── security/
│   │   ├── JwtTokenProvider.java
│   │   ├── JwtAuthenticationFilter.java
│   │   └── UserPrincipal.java
│   ├── exception/
│   │   ├── BusinessException.java
│   │   ├── GlobalExceptionHandler.java
│   │   └── ErrorResponse.java
│   └── model/
│       ├── ApiResponse.java
│       └── PageResponse.java
│
└── modules/
    ├── auth/                    # W1
    │   ├── entity/
    │   ├── dto/
    │   ├── repository/
    │   ├── service/
    │   └── controller/
    │
    ├── users/                  # W1
    ├── organizations/           # W1
    ├── stores/                 # W1
    ├── pets/                   # W1
    ├── products/               # W1
    ├── inventory/              # W1
    ├── appointments/           # W2
    ├── orders/                 # W2
    ├── payments/               # W2
    ├── invoices/               # W3
    ├── refunds/                # W3
    ├── clinical/               # W3
    ├── promotions/             # W3
    ├── vaccinations/          # W3
    ├── notifications/          # W5
    ├── walkins/               # W5
    ├── grooming/              # W6
    ├── workforce/              # W6
    ├── reports/               # W6
    └── audit/                 # W6

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__core_enums.sql          # 9 user_role + status enums
    ├── V2__accounts_users.sql      # Account, User, OTP
    ├── V3__organizations_stores.sql # Organization, Store, OperatingHours
    ├── V4__pets.sql                # Pet entity
    ├── V5__services_products.sql   # Services, Products
    ├── V5b__store_resources.sql    # StoreResources + RequiredResources (C-565e7b1)
    ├── V6__inventory.sql           # Inventory (PhysicalQuantity + ReservedQuantity)
    ├── V7__appointments.sql        # Appointment FSM
    ├── V8__orders.sql              # Order + Cart FSM
    ├── V9__payments.sql            # Payment FSM (with PARTIALLY_REFUNDED)
    ├── V10__invoices.sql           # Invoice FSM
    ├── V11__refunds.sql            # Refund FSM
    ├── V12__clinical.sql           # MedicalRecord + CrossStoreConsent (C-565e7b1)
    ├── V13__vaccinations.sql       # Vaccination + VaccineBatch (with barcode - C-565e7b1)
    ├── V14__promotions.sql         # Voucher
    ├── V15__notifications.sql      # Notification
    ├── V16__walkins.sql            # Walkin + Queue + QueueEntry (C-565e7b1)
    ├── V17__grooming.sql           # Grooming FSM (with AWAITING_CUSTOMER_APPROVAL)
    ├── V18__workforce.sql          # WorkSchedule + Leave
    ├── V19__reports.sql            # Read-only aggregates
    ├── V20__audit_logs.sql         # AuditLog + EMERGENCY_ACCESS_LOG
    └── V21__outbox_events.sql      # Transactional Outbox (D-04)
    └── ...

src/test/java/com/petcare/
└── modules/
    ├── auth/
    ├── appointments/
    └── ...
```

---

## 8. Step 6: Flyway Migrations

```bash
# Chạy migrations
mvn flyway:migrate

# Hoặc tạo migration mới
mvn flyway:create -Dflyway.name=V1__init_schema
```

---

## 9. Step 7: Base Classes

### 9.1 Main Application

```java
@SpringBootApplication
@EnableJpaAuditing
public class PetcareApplication {
    public static void main(String[] args) {
        SpringApplication.run(PetcareApplication.class, args);
    }
}
```

### 9.2 ApiResponse

```java
public record ApiResponse<T>(
    T data,
    String message,
    int code
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, "success", 200);
    }

    public static <T> ApiResponse<T> created(T data, String message) {
        return new ApiResponse<>(data, message, 201);
    }
}
```

### 9.3 Security Config

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtFilter;
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
```

---

## 10. Step 8: Run & Test

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/petcare-api-1.0.0.jar

# Test health
curl http://localhost:8080/actuator/health

# Test auth
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"0912345678","password":"test123","name":"Test User"}'
```

---

## 11. Week-by-Week Setup

### W1: Foundation

| Day | P1 | P2 | P3 |
|-----|-----|-----|-----|
| Mon | Create project + Docker | — | — |
| Tue | Auth module | — | — |
| Wed | Users + Organizations | Pets entity | Products entity |
| Thu | Stores + Operating hours + StoreResources | PetService | ProductService |
| Fri | Inventory (with PhysicalQuantity + ReservedQuantity) | Pet validations | Inventory module |

### W2: Core FSM

| Day | P1 | P2 | P3 |
|-----|-----|-----|-----|
| Mon | Appointments entity | Cart entity | Payment entity |
| Tue | Appointments FSM | Orders FSM | Payments FSM |
| Wed | Appointment tests (with StoreResource collision) | Order tests (with Inventory Reserve 15min TTL) | Payment tests (with PARTIALLY_REFUNDED) |
| Thu | RescheduleAppointment | CancelOrderWithRefund | Partial refund flow |
| Fri | **Demo M2** | **Demo M2** | **Demo M2** |

---

## 12. Verification Checklist

- [ ] Java 21 installed
- [ ] Maven 3.9+ installed
- [ ] Docker running
- [ ] Project compiles (`mvn clean compile`)
- [ ] Docker Compose up
- [ ] Flyway migrations run
- [ ] Health endpoint returns 200
- [ ] Auth register works
- [ ] Auth login works

---

## 13. Common Issues

### Issue: Flyway migration fails

```bash
# Xóa và tạo lại database
docker exec -it petcare-postgres psql -U postgres -c "DROP DATABASE petcare"
docker exec -it petcare-postgres psql -U postgres -c "CREATE DATABASE petcare"
mvn flyway:migrate
```

### Issue: Port already in use

```yaml
# Sửa port trong docker-compose.yml
ports:
  - "5433:5432"  # Đổi sang 5433
```

### Issue: JWT secret too short

```yaml
# Chắc chắn secret có ít nhất 32 ký tự
jwt:
  secret: your-256-bit-secret-key-here-min-32-characters-long
```