# Postman

Snapshot cua collection E2E, de diff va restore duoc ngoai lich su cua Postman.
Nguon chan ly cua team van la workspace Postman `pet-care-team` (fork -> PR -> merge);
file o day la ban xuat, khong phai be mat de sua truc tiep.

## Chay

Backend phai chay kem seed account, vi collection dang nhap bang tai khoan seed
thay vi di qua luong OTP:

```bash
docker run -d --name pc-db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=petcare_e2e -p 55433:5432 postgres:17
docker run -d --name pc-redis -p 6379:6379 redis:7-alpine

cd BE && mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Duser.timezone=UTC" \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:55433/petcare_e2e \
--spring.datasource.username=postgres --spring.datasource.password=postgres \
--spring.mail.username= --app.dev-seed.enabled=true"
```

`--spring.mail.username=` de trong -> `LogEmailGateway` thay `GmailSmtpEmailGateway`,
nen khong can Gmail App Password.

```bash
npx newman run postman/caregiver-delegation.postman_collection.json \
  -e postman/Local.postman_environment.json
```

Ket qua mong doi: 30 request, 118 assertion, 0 fail. Chay lai nhieu lan van xanh —
moi lan chay tu sinh email moi nen khong dung unique index `uq_pcd_outstanding`.

## Luu y

- Import vao Postman GUI: File > Import, chon ca 2 file, roi chon environment `Local`.
- Moi request tu dinh nghia lai `assertSuccess`/`assertError` trong tab Tests.
  Day la co y — xem `.claude/skills/postman-api-testing/references/pm-scripts.md`.
- Khong bao gio commit environment chua gia tri that cua staging/production.

## Ve `seed_password`

Collection co chua `Petcare@123` o collection variable `seed_password`. Day KHONG phai
secret bi lo: no la mat khau seed dev da hardcode san trong
`BE/src/main/java/com/petcare/platform/bootstrap/DevAccountSeeder.java` (da commit) va
duoc in ra log luc khoi dong. Cac tai khoan do chi ton tai khi
`app.dev-seed.enabled=true`, mac dinh TAT.

Tuyet doi khong dung collection nay voi environment tro toi staging/production. Collection
da co guard chan request destructive khi `env_type=production`, nhung guard khong thay the
viec chon dung environment. Mat khau that cua tai khoan that thuoc ve Postman Vault.
