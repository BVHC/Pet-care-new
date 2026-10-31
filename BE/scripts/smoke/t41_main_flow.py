#!/usr/bin/env python3
"""T41.4 — smoke Docker cho luồng chính (docs/testcases/T41-be.md §10).

Dựng stack riêng `petcare-smoke` (volume tạm, cron ST04 / ST18 mỗi phút), chèn dữ liệu nền bằng SQL, rồi chạy nhánh tiêm
(T41.2) và nhánh khám (T41.3) qua API thật, theo giờ thật. In một bảng: mỗi bước một dòng (HTTP status, trường chính,
OK / FAIL); email nhắc nằm trong Mailpit http://localhost:8025. Mã thoát ≠ 0 khi có bước sai.

Chạy từ gốc repo (Docker Desktop đang chạy, có `.env`, cổng 8080 / 8025 / 1025 trống):
    python BE/scripts/smoke/t41_main_flow.py          # giữ stack để xem Mailpit
    python BE/scripts/smoke/t41_main_flow.py --down   # dọn stack + volume sau khi chạy

Chỉ dùng thư viện chuẩn. Body JSON gửi bằng urllib (UTF-8) và SQL gửi vào psql qua stdin: `curl.exe` trên Windows làm hỏng
tiếng Việt truyền qua đối số.
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PROJECT = "petcare-smoke"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", str(ROOT / "docker-compose.yml"),
           "-f", str(ROOT / "BE" / "scripts" / "smoke" / "docker-compose.smoke.yml")]
SERVICES = ["postgres", "redis", "mailpit", "backend"]
PG_CONTAINER = "petcare-smoke-postgres"
API = "http://localhost:8080"
MAILPIT = "http://localhost:8025"
VN = timezone(timedelta(hours=7))  # Asia/Ho_Chi_Minh, không có giờ mùa hè

PASSWORD = "Petcare123"
# BCrypt 10 của PASSWORD (sinh một lần bằng bcrypt.hashpw; BCryptPasswordEncoder của Spring nhận tiền tố $2b$)
PASSWORD_HASH = "$2b$10$OKAd.fhz0W62YeC9IHDn/uYO20po4Cqlw3MgtJvQhxwAD7AgP1kIC"

S = 150_000  # giá dịch vụ tiêm / khám
V = 150_000  # giá vaccine
G = 40_000   # giá hàng
A = 30_000   # giá thuốc

rows = []
failures = []


# ============================================================== hạ tầng

def run(cmd, **kwargs):
    return subprocess.run(cmd, cwd=ROOT, check=True, **kwargs)


def sql(query):
    """Chạy SQL trong container postgres, trả các dòng kết quả (cột phân tách bằng '|')."""
    result = subprocess.run(
        ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", "postgres", "-d", "petcare", "-v", "ON_ERROR_STOP=1",
         "-At", "-q"],
        input=query.encode("utf-8"), capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError("SQL lỗi: " + result.stderr.decode("utf-8", "replace") + "\n" + query)
    return [line for line in result.stdout.decode("utf-8").splitlines() if line]


def one(query):
    lines = sql(query)
    if len(lines) != 1:
        raise RuntimeError(f"Mong 1 dòng, nhận {len(lines)}: {query}")
    return lines[0]


def q(value):
    """Literal SQL: None → NULL, số giữ nguyên, còn lại là chuỗi có nháy."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def http(method, path, token=None, body=None, base=API):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {"raw": raw.decode("utf-8", "replace")}


def record(no, branch, actor, request, status, summary, checks):
    """Một dòng bảng; {@code checks} là danh sách (điều kiện, mô tả khi sai)."""
    bad = [message for ok, message in checks if not ok]
    rows.append((str(no), branch, actor, request, str(status), summary, "OK" if not bad else "FAIL"))
    for message in bad:
        failures.append(f"bước {no}: {message}")


def data_of(body):
    return (body or {}).get("data") or {}


def print_table():
    headers = ("#", "Nhánh", "Vai trò", "Request", "HTTP", "Kết quả chính", "")
    widths = [max(len(headers[i]), *(len(r[i]) for r in rows)) for i in range(len(headers))]
    widths[5] = min(widths[5], 110)

    def line(cells):
        out = []
        for i, cell in enumerate(cells):
            text = cell if len(cell) <= widths[i] else cell[:widths[i] - 1] + "…"
            out.append(text.ljust(widths[i]))
        return "| " + " | ".join(out) + " |"

    print(line(headers))
    print("|" + "|".join("-" * (w + 2) for w in widths) + "|")
    for r in rows:
        print(line(r))


# ============================================================== môi trường

def start_stack():
    print("» Dọn stack smoke cũ (chỉ project petcare-smoke) và dựng lại …", flush=True)
    run(COMPOSE + ["down", "-v", "--remove-orphans"], capture_output=True)
    run(COMPOSE + ["up", "-d", "--build"] + SERVICES)
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            status, body = http("GET", "/actuator/health")
            if status == 200 and (body or {}).get("status") == "UP":
                print("» Backend UP", flush=True)
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(3)
    raise RuntimeError("Backend không UP sau 300 s: xem `docker logs petcare-smoke-api`")


def next_slot(now):
    """Khung 30 phút kế tiếp bắt đầu sau ít nhất 2 phút (để kịp đặt trước khi khung bắt đầu)."""
    earliest = now + timedelta(minutes=2)
    floor = earliest.replace(minute=(earliest.minute // 30) * 30, second=0, microsecond=0)
    return floor if floor == earliest else floor + timedelta(minutes=30)


# ============================================================== dữ liệu nền (SQL)

def seed(today):
    tag = uuid.uuid4().hex[:8]
    ids = {"tag": tag, "email": f"smoke-{tag}@petcare.local"}

    ids["branch"] = int(one(f"""
        INSERT INTO branches (name, address, phone, latitude, longitude, status, activated_at)
        VALUES ({q('Chi nhánh Smoke ' + tag)}, '1 Đường Smoke', '0280000000', 10.762622, 106.660172, 'ACTIVE', now())
        RETURNING id"""))
    ids["branch_name"] = "Chi nhánh Smoke " + tag
    for day in range(1, 8):
        sql(f"""INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                VALUES ({ids['branch']}, {q(today - timedelta(days=7))}, {day}, '00:00', '23:59')""")

    def account(role, email, staff_branch):
        # ck_accounts_phone_by_role: nhân viên bắt buộc SĐT, tài khoản khách không có SĐT (SĐT nằm ở customers)
        phone = None if role == "CUSTOMER" else "0901234567"
        account_id = int(one(f"""
            INSERT INTO accounts (email, phone, password_hash, role, status)
            VALUES ({q(email)}, {q(phone)}, {q(PASSWORD_HASH)}, {q(role)}, 'ACTIVE') RETURNING id"""))
        if role != "CUSTOMER":
            sql(f"""INSERT INTO staff_profiles (account_id, full_name, branch_id)
                    VALUES ({account_id}, {q(role.title() + ' Smoke')}, {q(staff_branch)})""")
        return account_id

    ids["admin_email"] = f"admin-{tag}@petcare.local"
    ids["receptionist_email"] = f"letan-{tag}@petcare.local"
    ids["vet_email"] = f"bacsi-{tag}@petcare.local"
    ids["admin"] = account("ADMIN", ids["admin_email"], None)
    ids["receptionist"] = account("RECEPTIONIST", ids["receptionist_email"], ids["branch"])
    ids["vet"] = account("VET", ids["vet_email"], ids["branch"])
    ids["account"] = account("CUSTOMER", ids["email"], None)
    sql(f"""INSERT INTO branch_quota_defaults (branch_id, service_group, default_quota, updated_by)
            VALUES ({ids['branch']}, 'MEDICAL', 5, {ids['admin']})""")

    ids["customer"] = int(one(f"""
        INSERT INTO customers (account_id, full_name, created_channel)
        VALUES ({ids['account']}, 'Khách Smoke {tag}', 'ONLINE') RETURNING id"""))

    def pet(name):
        return int(one(f"""
            INSERT INTO pets (customer_id, name, species, sex, birth_date)
            VALUES ({ids['customer']}, {q(name)}, 'DOG', 'MALE', {q(today - timedelta(days=730))}) RETURNING id"""))

    ids["pet_v"] = pet("Milu")
    ids["pet_e"] = pet("Bông")

    def service(name, medical_type):
        service_id = int(one(f"""
            INSERT INTO services (name, service_group, medical_type, price)
            VALUES ({q(name + ' ' + tag)}, 'MEDICAL', {q(medical_type)}, {S}) RETURNING id"""))
        sql(f"INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES ({ids['branch']}, {service_id}, true)")
        return service_id

    ids["service_vaccine"] = service("Tiêm phòng dại", "VACCINE")
    ids["service_exam"] = service("Khám tổng quát", "EXAM")

    category = int(one(f"INSERT INTO product_categories (name) VALUES ({q('Smoke ' + tag)}) RETURNING id"))
    ids["vaccine_type"] = int(one(f"INSERT INTO vaccine_types (name, species) VALUES ({q('Dại ' + tag)}, 'DOG') RETURNING id"))

    def protocol(dose):
        # khoảng cách 7 ngày: next_due_date = hôm nay + 7 nằm ngay trong cửa sổ ST04 (reminder.vaccine_days_before = 7)
        return int(one(f"""
            INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks,
                    required_for_boarding)
            VALUES ('DOG', {ids['vaccine_type']}, {dose}, 7, 6, false) RETURNING id"""))

    ids["dose1"] = protocol(1)
    ids["dose2"] = protocol(2)

    def product(kind, name, price, extra_cols="", extra_vals=""):
        return int(one(f"""
            INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price{extra_cols})
            VALUES ({category}, {q(kind[:3] + '-' + tag + '-' + name[:4])}, {q(name + ' ' + tag)}, {q(kind)}, true,
                    'đơn vị', {price}{extra_vals}) RETURNING id"""))

    ids["vaccine"] = product("VACCINE", "Rabisin", V, ", vaccine_type_id", f", {ids['vaccine_type']}")
    ids["goods"] = product("GOODS", "Pate", G)
    ids["drug_a"] = product("DRUG", "Amoxicillin", A, ", is_prescription", ", true")
    ids["drug_b"] = product("DRUG", "Metronidazole", A, ", is_prescription", ", true")

    def lot(product_id, expiry_offset, quantity):
        item = int(one(f"""
            INSERT INTO inventory_items (branch_id, product_id) VALUES ({ids['branch']}, {product_id})
            ON CONFLICT (branch_id, product_id) DO UPDATE SET min_quantity = inventory_items.min_quantity
            RETURNING id"""))
        return int(one(f"""
            INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
            VALUES ({item}, {q('LOT-' + uuid.uuid4().hex[:8])}, {q(today + timedelta(days=expiry_offset))}, {quantity},
                    false) RETURNING id"""))

    ids["lots_vaccine"] = [lot(ids["vaccine"], -1, 5), lot(ids["vaccine"], 300, 5), lot(ids["vaccine"], 60, 5)]
    ids["lots_goods"] = [lot(ids["goods"], -1, 5), lot(ids["goods"], 300, 10), lot(ids["goods"], 60, 2)]
    ids["lots_a"] = [lot(ids["drug_a"], -1, 5), lot(ids["drug_a"], 200, 3), lot(ids["drug_a"], 30, 2)]
    ids["lots_b"] = [lot(ids["drug_b"], -1, 5)]

    def old_visit(pet_id, day):
        at = f"{day.isoformat()} 09:00:00+07"
        return int(one(f"""
            INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                    queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
            VALUES ({q('VS-SMOKE-' + uuid.uuid4().hex[:8])}, {ids['branch']}, {ids['customer']}, {pet_id}, 'MEDICAL',
                    'COMPLETED', 'WALK_IN', {q(at)}, {ids['vet']}, {ids['receptionist']}, {q(at)}, {q(at)}, {q(at)})
            RETURNING id"""))

    # Thú V: mũi 1 cũ hôm nay − 30, quá hạn; ST18 đã chạy (overdue_handled_at) và sinh Care Task VACCINE_OVERDUE OPEN
    ids["old_visit_v"] = old_visit(ids["pet_v"], today - timedelta(days=30))
    old_order = int(one(f"""
        INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
        VALUES ({q('OD-SMOKE-' + uuid.uuid4().hex[:8])}, 'VISIT', {ids['customer']}, {ids['branch']},
                {ids['old_visit_v']}, 'OPEN', {ids['vet']}) RETURNING id"""))
    old_line = int(one(f"""
        INSERT INTO order_lines (order_id, line_type, product_id, description, quantity, unit_price, line_total, added_by,
                owner_account_id)
        VALUES ({old_order}, 'VACCINE', {ids['vaccine']}, 'Vaccine smoke', 1, {V}, {V}, {ids['vet']}, {ids['vet']})
        RETURNING id"""))
    ids["old_dose"] = int(one(f"""
        INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id, dose_number,
                product_id, stock_lot_id, order_line_id, next_due_date, recorded_by, overdue_handled_at)
        VALUES ({ids['pet_v']}, {ids['vaccine_type']}, {ids['old_visit_v']}, {ids['branch']},
                {q(today - timedelta(days=30))}, {ids['dose1']}, 1, {ids['vaccine']}, {ids['lots_vaccine'][0]}, {old_line},
                {q(today - timedelta(days=9))}, {ids['vet']}, now() - interval '2 days') RETURNING id"""))
    ids["old_task"] = int(one(f"""
        INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, vaccination_id, due_date, status)
        VALUES ({ids['branch']}, {ids['customer']}, {ids['pet_v']}, 'VACCINE_OVERDUE', {ids['old_dose']},
                {q(today - timedelta(days=2))}, 'OPEN') RETURNING id"""))

    # Thú E: bệnh án cũ R0 đã khóa, hẹn tái khám hôm nay + 10 (ngoài cửa sổ 3 ngày nên ST04 mỗi phút không nhắc trước)
    ids["old_visit_e"] = old_visit(ids["pet_e"], today - timedelta(days=20))
    sql(f"""INSERT INTO medical_records (visit_id, follow_up_date, locked_at)
            VALUES ({ids['old_visit_e']}, {q(today + timedelta(days=10))}, {q((today - timedelta(days=20)).isoformat() + ' 09:00:00+07')})""")
    return ids


# ============================================================== truy vấn kiểm

def lots(lot_ids):
    return [int(one(f"SELECT quantity FROM stock_lots WHERE id = {lot_id}")) for lot_id in lot_ids]


def login(email):
    status, body = http("POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    return status, data_of(body).get("accessToken")


def mailpit_reminders(email):
    query = urllib.parse.quote(f'to:"{email}"')
    status, body = http("GET", f"/api/v1/search?query={query}&limit=50", base=MAILPIT)
    subjects = [m.get("Subject", "") for m in (body or {}).get("messages", [])]
    return [s for s in subjects if s.startswith("Nhắc tiêm") or s.startswith("Nhắc tái khám")]


def reminder_outbox(ids):
    return sql(f"""
        SELECT template_code || ':' || channel FROM notification_outbox
        WHERE (recipient_account_id = {ids['account']} OR recipient_email = {q(ids['email'])})
          AND template_code IN ('VACCINE_REMINDER', 'VACCINE_REMINDER_APP', 'FOLLOW_UP_REMINDER', 'FOLLOW_UP_REMINDER_APP')
        ORDER BY template_code""")


def wait_until(predicate, timeout, step=5):
    deadline = time.time() + timeout
    while True:
        result = predicate()
        if result or time.time() >= deadline:
            return result
        time.sleep(step)


# ============================================================== kịch bản

def scenario(ids, today, slot):
    lv, lg, la, lb = ids["lots_vaccine"], ids["lots_goods"], ids["lots_a"], ids["lots_b"]

    # 1 — ADMIN hạ min_lead_hours để đặt online được khung kế tiếp (giờ thật, volume tạm)
    st, admin = login(ids["admin_email"])
    st2, body = http("PUT", "/api/system-configs/appointment.min_lead_hours", admin, {"value": "0"})
    value = data_of(body).get("value")
    record(1, "—", "ADMIN", "login; PUT /api/system-configs/appointment.min_lead_hours", f"{st}/{st2}",
           f"value={value}", [(st == 200 and st2 == 200, f"HTTP {st}/{st2} {body}"), (str(value) == "0", "value ≠ 0")])

    # 2–3 — A02 đặt lịch cho 2 thú cùng khung; đặt MEDICAL hủy nhắc tái khám chờ gửi của R0 (ADR-0084)
    st, customer = login(ids["email"])
    booking = {"branchId": ids["branch"], "slotDate": slot.date().isoformat(), "slotStart": slot.strftime("%H:%M")}
    st2, body = http("POST", "/api/appointments", customer,
                     {**booking, "petId": ids["pet_v"], "serviceId": ids["service_vaccine"]})
    appt_v = data_of(body).get("appointmentId")
    code_v = one(f"SELECT code || ' ' || status || ' ' || channel FROM appointments WHERE id = {appt_v or 0}") if appt_v else "-"
    record(2, "V", "A02", "login; POST /api/appointments (tiêm)", f"{st}/{st2}", code_v,
           [(st2 == 201, f"HTTP {st2} {body}"), (code_v.endswith("BOOKED ONLINE"), code_v)])

    st, body = http("POST", "/api/appointments", customer,
                    {**booking, "petId": ids["pet_e"], "serviceId": ids["service_exam"]})
    appt_e = data_of(body).get("appointmentId")
    r0 = one(f"""SELECT coalesce((follow_up_cancelled_at IS NOT NULL)::text, '') || ' ' ||
                        coalesce((follow_up_reminded_at IS NOT NULL)::text, '')
                 FROM medical_records WHERE visit_id = {ids['old_visit_e']}""")
    record(3, "E", "A02", "POST /api/appointments (khám)", st, f"appointment={appt_e}; R0 hủy nhắc={r0.split()[0]}",
           [(st == 201, f"HTTP {st} {body}"), (r0 == "true false", f"R0 cancelled/reminded = {r0}")])

    # chờ tới 30 phút trước giờ hẹn nếu cần (visit.check_in_early_minutes = 30)
    wait = (slot - timedelta(minutes=30) - datetime.now(VN)).total_seconds()
    if wait > 0:
        print(f"» Chờ {int(wait) + 5} s tới cửa sổ check-in …", flush=True)
        time.sleep(wait + 5)

    st, receptionist = login(ids["receptionist_email"])
    st, vet = login(ids["vet_email"])

    def check_in(no, branch, pet_id, appt):
        st1, body = http("POST", "/api/visits", receptionist, {"petId": pet_id, "appointmentId": appt})
        visit = data_of(body).get("visitId")
        order = int(one(f"SELECT id FROM orders WHERE visit_id = {visit}")) if visit else 0
        st2, _ = http("POST", f"/api/visits/{visit}/assign", receptionist, {"assigneeId": ids["vet"]})
        st3, _ = http("POST", f"/api/visits/{visit}/call", vet)
        state = one(f"""SELECT v.status || ' ' || a.status || ' ' || o.status || ' ' || o.total_amount
                        FROM visits v JOIN appointments a ON a.id = v.appointment_id JOIN orders o ON o.visit_id = v.id
                        WHERE v.id = {visit or 0}""") if visit else "-"
        record(no, branch, "A06/A07", "POST /api/visits → …/assign → …/call", f"{st1}/{st2}/{st3}",
               f"visit={visit}; Visit/lịch/Order/tổng = {state}",
               [(st1 == 201 and st2 == 200 and st3 == 200, f"HTTP {st1}/{st2}/{st3} {body}"),
                (state == f"IN_PROGRESS CHECKED_IN OPEN {S}", state)])
        return visit, order

    # ---------------------------------------------------------------- nhánh tiêm (T41.2)
    visit_v, order_v = check_in(4, "V", ids["pet_v"], appt_v)

    st, body = http("GET", f"/api/visits/{visit_v}/vaccination-options?vaccineTypeId={ids['vaccine_type']}", vet)
    options = data_of(body)
    products = [(p.get("productId"), p.get("availableQuantity")) for p in options.get("products", [])]
    record(5, "V", "A07", "GET …/vaccination-options", st,
           f"gợi ý protocol={options.get('suggestedProtocolId')}; sản phẩm={products}",
           [(st == 200, f"HTTP {st} {body}"), (options.get("suggestedProtocolId") == ids["dose2"], "không gợi ý mũi 2"),
            (products == [(ids["vaccine"], 10)], f"khả dụng {products}")])

    st, body = http("POST", f"/api/visits/{visit_v}/vaccinations", vet,
                    {"vaccineTypeId": ids["vaccine_type"], "protocolId": ids["dose2"], "productId": ids["vaccine"]})
    q_lots = lots(lv)
    new_due = one(f"SELECT next_due_date FROM vaccinations WHERE visit_id = {visit_v or 0}") if st == 201 else "-"
    old = one(f"""SELECT (v.superseded_at IS NOT NULL)::text || ' ' || t.status FROM vaccinations v
                  JOIN care_tasks t ON t.vaccination_id = v.id WHERE v.id = {ids['old_dose']}""")
    record(6, "V", "A07", "POST …/vaccinations (mũi 2)", st,
           f"lô L0/L1/L2={q_lots}; next_due={new_due}; mũi cũ thay thế/task = {old}",
           [(st == 201, f"HTTP {st} {body}"), (q_lots == [5, 5, 4], f"lô {q_lots}"),
            (new_due == (today + timedelta(days=7)).isoformat(), f"next_due {new_due}"),
            (old == "true CANCELLED", f"mũi cũ {old}")])

    st, body = http("PUT", f"/api/visits/{visit_v}/medical-record", vet,
                    {"examination": "Thân nhiệt 38,5°C, khỏe, đủ điều kiện tiêm"})
    st2, body2 = http("POST", f"/api/visits/{visit_v}/complete", vet)
    state = one(f"""SELECT v.status || ' ' || a.status || ' ' || o.status || ' ' || (m.locked_at IS NOT NULL)::text
                    FROM visits v JOIN appointments a ON a.id = v.appointment_id JOIN orders o ON o.visit_id = v.id
                    JOIN medical_records m ON m.visit_id = v.id WHERE v.id = {visit_v}""")
    record(7, "V", "A07", "PUT …/medical-record; POST …/complete", f"{st}/{st2}",
           f"Visit/lịch/Order/bệnh án khóa = {state}",
           [(st == 200 and st2 == 200, f"HTTP {st}/{st2} {body} {body2}"),
            (state == "COMPLETED COMPLETED PENDING true", state)])

    st, body = http("PUT", f"/api/visits/{visit_v}/medical-record", vet, {"examination": "Sửa sau khi khóa"})
    record(8, "V", "A07", "PUT …/medical-record (đã khóa)", st, f"errorCode={(body or {}).get('errorCode')}",
           [(st == 409 and (body or {}).get("errorCode") == "INVALID_STATE_TRANSITION", f"HTTP {st} {body}")])

    st, body = http("POST", f"/api/orders/{order_v}/lines", receptionist,
                    {"lineType": "GOODS", "productId": ids["goods"], "quantity": 3})
    total_v = int(one(f"SELECT total_amount FROM orders WHERE id = {order_v}"))
    record(9, "V", "A06", "POST /api/orders/{id}/lines (GOODS × 3)", st,
           f"tổng={total_v}; stockWarning={data_of(body).get('stockWarning')}",
           [(st == 201, f"HTTP {st} {body}"), (total_v == S + V + 3 * G, f"tổng {total_v}")])

    # ---------------------------------------------------------------- nhánh khám (T41.3)
    visit_e, order_e = check_in(10, "E", ids["pet_e"], appt_e)

    st, body = http("POST", f"/api/visits/{visit_e}/complete", vet)
    record(11, "E", "A07", "POST …/complete (chưa chẩn đoán)", st, (body or {}).get("message", ""),
           [(st == 400 and str((body or {}).get("message", "")).endswith("(BR-KB-02)"), f"HTTP {st} {body}"),
            (one(f"SELECT status FROM visits WHERE id = {visit_e}") == "IN_PROGRESS", "Visit đổi trạng thái")])

    follow_up = today + timedelta(days=3)
    st, body = http("PUT", f"/api/visits/{visit_e}/medical-record", vet, {
        "examination": "Nôn 2 lần, bụng chướng nhẹ, thân nhiệt 39°C", "diagnosis": "Viêm dạ dày ruột cấp",
        "treatmentPlan": "Kháng sinh 5 ngày, men tiêu hóa", "followUpDate": follow_up.isoformat()})
    record(12, "E", "A07", "PUT …/medical-record (chẩn đoán, tái khám)", st,
           f"followUpDate={data_of(body).get('followUpDate')}; locked={data_of(body).get('locked')}",
           [(st == 200, f"HTTP {st} {body}"), (data_of(body).get("followUpDate") == follow_up.isoformat(), "ngày tái khám")])

    st, body = http("POST", f"/api/visits/{visit_e}/prescriptions", vet,
                    {"productId": ids["drug_a"], "quantity": 4, "dosageInstructions": "Uống 1 viên × 2 lần/ngày sau ăn"})
    item = data_of(body)
    total_e = int(one(f"SELECT total_amount FROM orders WHERE id = {order_e}"))
    record(13, "E", "A07", "POST …/prescriptions (A × 4)", st,
           f"mua ngoài={item.get('isExternalPurchase')}; orderLineId={item.get('orderLineId')}; tổng={total_e}",
           [(st == 201 and item.get("isExternalPurchase") is False and item.get("orderLineId"), f"HTTP {st} {body}"),
            (total_e == S + 4 * A, f"tổng {total_e}"), (lots(la) == [5, 3, 2], "kho thuốc A đổi trước khi thu")])

    st, body = http("POST", f"/api/visits/{visit_e}/prescriptions", vet,
                    {"productId": ids["drug_b"], "quantity": 2, "dosageInstructions": "Uống 1 viên × 2 lần/ngày"})
    st2, body2 = http("POST", f"/api/visits/{visit_e}/prescriptions", vet,
                      {"productId": ids["drug_b"], "quantity": 2, "dosageInstructions": "Uống 1 viên × 2 lần/ngày",
                       "shortageResolution": "EXTERNAL_PURCHASE"})
    item = data_of(body2)
    total_e = int(one(f"SELECT total_amount FROM orders WHERE id = {order_e}"))
    record(14, "E", "A07", "POST …/prescriptions (B × 2) → + EXTERNAL_PURCHASE", f"{st}/{st2}",
           f"{str((body or {}).get('message', ''))[-11:]}; lý do={item.get('externalReason')}; tổng={total_e}",
           [(st == 400 and str((body or {}).get("message", "")).endswith("(BR-KB-03)"), f"HTTP {st} {body}"),
            (st2 == 201 and item.get("externalReason") == "OUT_OF_STOCK_AT_PRESCRIBE"
             and item.get("orderLineId") is None, f"HTTP {st2} {body2}"),
            (total_e == S + 4 * A, f"tổng {total_e}")])

    st, body = http("POST", f"/api/visits/{visit_e}/complete", vet)
    state = one(f"""SELECT v.status || ' ' || a.status || ' ' || o.status || ' ' || (m.locked_at IS NOT NULL)::text
                    FROM visits v JOIN appointments a ON a.id = v.appointment_id JOIN orders o ON o.visit_id = v.id
                    JOIN medical_records m ON m.visit_id = v.id WHERE v.id = {visit_e}""")
    record(15, "E", "A07", "POST …/complete", st, f"Visit/lịch/Order/bệnh án khóa = {state}",
           [(st == 200, f"HTTP {st} {body}"), (state == "COMPLETED COMPLETED PENDING true", state)])

    # ---------------------------------------------------------------- thu ngân
    st, body = http("POST", "/api/cashier-shifts", receptionist, {"afterHours": False})
    shift = data_of(body).get("shiftId")
    st2, body2 = http("POST", "/api/payments/preview", receptionist, {"orderIds": [order_v, order_e]})
    preview = data_of(body2)
    record(16, "—", "A06", "POST /api/cashier-shifts; POST /api/payments/preview (2 Order)", f"{st}/{st2}",
           f"shift={shift}; tổng={preview.get('totalAmount')}; thiếu hàng={preview.get('shortages')}",
           [(st == 201 and st2 == 200, f"HTTP {st}/{st2} {body} {body2}"),
            (preview.get("totalAmount") == S + V + 3 * G + S + 4 * A, f"tổng {preview.get('totalAmount')}"),
            (preview.get("shortages") == [], "có thiếu hàng")])

    st, body = http("POST", "/api/payments", receptionist,
                    {"orderIds": [order_v], "method": "CASH", "amount": S + V + 3 * G})
    goods = lots(lg)
    vaccine_moves = int(one(f"""SELECT count(*) FROM stock_movements
                                WHERE stock_lot_id IN ({','.join(map(str, lv))}) AND movement_type <> 'VACCINATION'"""))
    status_v = one(f"SELECT status FROM orders WHERE id = {order_v}")
    record(17, "V", "A06", "POST /api/payments (Order tiêm)", st,
           f"Order={status_v}; lô G0/G1/G2={goods}; vaccine trừ lần 2={vaccine_moves}",
           [(st == 201 and status_v == "PAID", f"HTTP {st} {body}"), (goods == [5, 9, 0], f"lô hàng {goods}"),
            (vaccine_moves == 0, "vaccine bị trừ lần 2"), (lots(lv) == [5, 5, 4], "lô vaccine đổi")])

    st, body = http("POST", "/api/payments", receptionist,
                    {"orderIds": [order_e], "method": "CASH", "amount": S + 4 * A})
    drug_a, drug_b = lots(la), lots(lb)
    status_e = one(f"SELECT status FROM orders WHERE id = {order_e}")
    record(18, "E", "A06", "POST /api/payments (Order khám)", st,
           f"Order={status_e}; lô A0/A1/A2={drug_a}; B0={drug_b}",
           [(st == 201 and status_e == "PAID", f"HTTP {st} {body}"), (drug_a == [5, 1, 0], f"lô A {drug_a}"),
            (drug_b == [5], f"lô B {drug_b}")])

    # ---------------------------------------------------------------- ST04 + ST20
    def flags():
        v = one(f"SELECT (due_reminded_at IS NOT NULL)::text FROM vaccinations WHERE visit_id = {visit_v}")
        e = one(f"SELECT (follow_up_reminded_at IS NOT NULL)::text FROM medical_records WHERE visit_id = {visit_e}")
        return v == "true" and e == "true"

    started = time.time()
    print("» Chờ ST04 (cron mỗi phút, giây 30 / 40) …", flush=True)
    reminded = wait_until(flags, 150)
    outbox = reminder_outbox(ids)
    record(19, "V,E", "job ST04", "VaccineReminderJob / FollowUpReminderJob (cron mỗi phút)", "—",
           f"sau {int(time.time() - started)} s; outbox={outbox}",
           [(reminded, "cờ nhắc chưa được đặt sau 150 s"),
            (outbox == ["FOLLOW_UP_REMINDER:EMAIL", "FOLLOW_UP_REMINDER_APP:IN_APP", "VACCINE_REMINDER:EMAIL",
                        "VACCINE_REMINDER_APP:IN_APP"], f"outbox {outbox}"),
            (int(one(f"SELECT count(*) FROM care_tasks WHERE customer_id = {ids['customer']} AND status = 'OPEN'")) == 0,
             "có Care Task OPEN")])

    mails = wait_until(lambda: (lambda m: m if len(m) >= 2 else None)(mailpit_reminders(ids["email"])), 90) or []
    record(20, "V,E", "Mailpit", f"GET {MAILPIT}/api/v1/search?query=to:<email>", 200, "; ".join(mails),
           [(len(mails) == 2 and any(m.startswith("Nhắc tiêm") for m in mails)
             and any(m.startswith("Nhắc tái khám") for m in mails), f"thư nhắc {mails}")])

    def in_app():
        st, body = http("GET", "/api/me/notifications?size=50", customer)
        items = [n for n in data_of(body).get("content", []) if str(n.get("title", "")).startswith("Sắp đến ngày")]
        return (st, items) if len(items) >= 2 else None

    found = wait_until(in_app, 60) or (None, [])
    titles = [n.get("title") for n in found[1]]
    record(21, "V,E", "A02", "GET /api/me/notifications", found[0], "; ".join(titles),
           [(len(titles) == 2, f"thông báo IN_APP {titles}"),
            (all(n.get("linkUrl", "").startswith(f"/booking?petId=") for n in found[1]), "link đặt lịch")])

    # chạy qua thêm một lượt cron của cả hai job: không gửi trùng (ADR-0082)
    now = datetime.now(VN)
    pause = (now.replace(second=45, microsecond=0) + timedelta(minutes=1) - now).total_seconds()
    print(f"» Chờ {int(pause)} s qua thêm một lượt cron …", flush=True)
    time.sleep(pause)
    outbox_again = reminder_outbox(ids)
    mails_again = mailpit_reminders(ids["email"])
    record(22, "V,E", "job ST04", "chạy lại sau một phút", "—",
           f"outbox={len(outbox_again)} dòng; thư nhắc={len(mails_again)}",
           [(outbox_again == outbox, f"outbox đổi {outbox_again}"), (len(mails_again) == 2, f"thư {mails_again}")])


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="T41.4 smoke Docker luồng chính (docs/testcases/T41-be.md §10)")
    parser.add_argument("--down", action="store_true", help="dọn stack và volume petcare-smoke sau khi chạy")
    args = parser.parse_args()

    now = datetime.now(VN)
    if now.hour == 23:
        sys.exit("Không chạy từ 23:00 đến 24:00 giờ VN: không còn khung 30 phút nào trong ngày (chi nhánh smoke mở "
                 "00:00–23:59). Chạy lại sau 00:00.")
    if not (ROOT / ".env").exists():
        sys.exit("Thiếu .env ở gốc repo: cp .env.example .env (docker compose cần JWT_SECRET).")

    start_stack()
    today = datetime.now(VN).date()
    slot = next_slot(datetime.now(VN))
    if slot.date() != today or (slot.hour, slot.minute) == (23, 30):
        sys.exit(f"Khung kế tiếp {slot:%H:%M} không còn trong ngày; chạy lại sau 00:00.")
    ids = seed(today)
    print(f"» Dữ liệu nền xong: chi nhánh {ids['branch']}, khách {ids['email']} / {PASSWORD}, khung {slot:%Y-%m-%d %H:%M}",
          flush=True)

    try:
        scenario(ids, today, slot)
    except Exception as error:  # in bảng tới bước đã chạy rồi báo lỗi
        failures.append(f"dừng giữa chừng: {error!r}")

    print()
    print_table()
    print()
    print(f"Mailpit: {MAILPIT} (thư tới {ids['email']}) · Swagger: {API}/swagger-ui.html")
    if failures:
        print(f"FAIL — {len(failures)} lỗi:")
        for failure in failures:
            print("  - " + failure)
    else:
        print(f"PASS — {len(rows)}/{len(rows)} bước OK")
    down = " ".join(COMPOSE + ["down", "-v"])
    if args.down:
        run(COMPOSE + ["down", "-v"], capture_output=True)
        print("Đã dọn stack smoke.")
    else:
        print(f"Dọn stack: {down}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
