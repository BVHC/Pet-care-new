"""Sinh monitoring/grafana/dashboards/petcare-overview.json (docs/adr/0155). Chạy lại khi đổi panel.

Từ gốc repo: python monitoring/grafana/tools/build_overview.py monitoring/grafana/dashboards/petcare-overview.json
"""
import json
import sys
from pathlib import Path

DST = Path(sys.argv[1])
DS = {"type": "prometheus", "uid": "prometheus"}
APP = 'application="petcare-api"'
HTTP = f'{APP}, uri!~"/actuator.*|/livez|/readyz"'

panels = []
y = 0
next_id = 1


def _id():
    global next_id
    next_id += 1
    return next_id - 1


def row(title):
    global y
    panels.append({"type": "row", "title": title, "id": _id(), "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
    y += 1


def target(expr, legend="", instant=False, fmt=None):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": "A", "range": not instant,
         "instant": instant}
    if fmt:
        t["format"] = fmt
    return t


def refs(targets):
    for i, t in enumerate(targets):
        t["refId"] = chr(ord("A") + i)
    return targets


def stat(title, x, w, targets, unit="short", description="", thresholds=None, decimals=None, h=4, mappings=None):
    steps = thresholds or [{"color": "green", "value": None}]
    defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}, "mappings": mappings or []}
    if decimals is not None:
        defaults["decimals"] = decimals
    return {"type": "stat", "title": title, "description": description, "id": _id(), "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y}, "targets": refs(targets),
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "area", "justifyMode": "auto", "textMode": "auto"}}


def series(title, x, w, targets, unit="short", description="", h=8, stack=False, bars=False):
    custom = {"drawStyle": "bars" if bars else "line", "lineWidth": 1, "fillOpacity": 30 if bars else 10,
              "showPoints": "never", "spanNulls": True,
              "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
    return {"type": "timeseries", "title": title, "description": description, "id": _id(), "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y}, "targets": refs(targets),
            "fieldConfig": {"defaults": {"unit": unit, "custom": custom}, "overrides": []},
            "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["lastNotNull", "max"]},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def table(title, x, w, expr, columns, description="", h=8):
    return {"type": "table", "title": title, "description": description, "id": _id(), "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y},
            "targets": refs([target(expr, instant=True, fmt="table")]),
            "fieldConfig": {"defaults": {}, "overrides": []},
            "options": {"showHeader": True, "sortBy": [{"displayName": "Value", "desc": True}]},
            "transformations": [{"id": "organize", "options": {
                "excludeByName": {"Time": True},
                "renameByName": columns}}]}


def advance(h):
    global y
    y += h


RED = [{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 5}]

# --- Tổng quan -------------------------------------------------------------------------------------------------------
row("Tổng quan")
panels += [
    stat("Backend", 0, 3, [target('max(up{job="petcare-api"})', instant=True)],
         description="1 = Prometheus đọc được /actuator/prometheus của BE.",
         thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}],
         mappings=[{"type": "value", "options": {"0": {"text": "DOWN"}, "1": {"text": "UP"}}}]),
    stat("Request / giây", 3, 4, [target(f"sum(rate(http_server_requests_seconds_count{{{HTTP}}}[5m]))")],
         unit="reqps", decimals=2),
    stat("Tỷ lệ lỗi 5xx", 7, 4, [target(
        f'100 * sum(rate(http_server_requests_seconds_count{{{HTTP}, status=~"5.."}}[5m])) '
        f"/ clamp_min(sum(rate(http_server_requests_seconds_count{{{HTTP}}}[5m])), 1e-9)")],
         unit="percent", decimals=2, thresholds=RED),
    stat("Độ trễ p95", 11, 4, [target(
        f"histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{HTTP}}}[5m])))")],
         unit="s", decimals=3,
         thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.5},
                     {"color": "red", "value": 2}]),
    stat("Heap JVM", 15, 3, [target(
        f'100 * sum(jvm_memory_used_bytes{{{APP}, area="heap"}}) / sum(jvm_memory_max_bytes{{{APP}, area="heap"}} > 0)')],
         unit="percent", decimals=0,
         thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 75}, {"color": "red", "value": 90}]),
    stat("Kết nối DB đang dùng", 18, 3, [target(f"sum(hikaricp_connections_active{{{APP}}})")],
         description="HikariCP: kết nối đang mượn / tổng pool ở dashboard Spring Boot."),
    stat("Job lỗi (24 giờ)", 21, 3, [target(
        'sum(increase(petcare_job_runs_total{outcome="failed"}[24h])) or vector(0)', instant=True)],
         decimals=0, thresholds=RED, description="Lượt chạy job rơi vào nhánh <NAME>_FAILED ngoài cùng."),
]
advance(4)

# --- HTTP ------------------------------------------------------------------------------------------------------------
row("HTTP")
panels += [
    series("Request / giây theo nhóm mã", 0, 8, [target(
        f'sum by (status) (label_replace(rate(http_server_requests_seconds_count{{{HTTP}}}[5m]), '
        f'"status", "${{1}}xx", "status", "(.).."))', "{{status}}")], unit="reqps", stack=True),
    series("p95 theo endpoint (top 10)", 8, 8, [target(
        f"topk(10, histogram_quantile(0.95, sum by (le, method, uri) "
        f"(rate(http_server_requests_seconds_bucket{{{HTTP}}}[5m]))))", "{{method}} {{uri}}")], unit="s"),
    series("Lỗi 4xx / 5xx theo endpoint", 16, 8, [target(
        f'sum by (method, uri, status) (rate(http_server_requests_seconds_count{{{HTTP}, status=~"[45].."}}[5m])) > 0',
        "{{status}} {{method}} {{uri}}")], unit="reqps",
           description="429 là rate limit; 409 / 400 là lỗi nghiệp vụ bình thường, 5xx mới đáng lo."),
]
advance(8)

# --- Thông báo ST20 --------------------------------------------------------------------------------------------------
row("Thông báo ST20 (outbox)")
panels += [
    series("Đang chờ gửi theo lane", 0, 8, [target(f"notification_outbox_pending{{{APP}}}", "{{lane}}")],
           description="Số dòng PENDING đến hạn của mỗi lane sau lượt job gần nhất (docs/adr/0012, 0017, 0154)."),
    series("Dòng cũ nhất đã quá hạn", 8, 8, [target(
        f"notification_outbox_oldest_overdue_seconds{{{APP}}}", "{{lane}}")], unit="s",
           description="Tăng đều là worker không theo kịp hoặc SMTP lỗi."),
    series("Kết quả gửi / phút", 16, 8, [target(
        f"sum by (lane, result) (rate(notification_outbox_result_total{{{APP}}}[5m])) * 60", "{{lane}} {{result}}")],
           stack=True),
]
advance(8)
panels += [
    series("Thời gian chờ trong hàng (trung bình / max)", 0, 12, [
        target(f"sum by (lane) (rate(notification_outbox_queue_latency_seconds_sum{{{APP}}}[5m])) / "
               f"sum by (lane) (rate(notification_outbox_queue_latency_seconds_count{{{APP}}}[5m]))", "tb {{lane}}"),
        target(f"max by (lane) (notification_outbox_queue_latency_seconds_max{{{APP}}})", "max {{lane}}")],
           unit="s", h=7),
    series("Lô SMTP / phút", 12, 12, [target(
        f"sum by (lane) (rate(notification_outbox_smtp_batches_total{{{APP}}}[5m])) * 60", "{{lane}}")], h=7),
]
advance(7)

# --- Job -------------------------------------------------------------------------------------------------------------
row("Job định kỳ")
panels += [
    table("Lượt chạy theo job (24 giờ)", 0, 8,
          "sum by (job, outcome) (increase(petcare_job_runs_total[24h]))",
          {"job": "Job", "outcome": "Kết quả", "Value": "Số lượt"}, h=10),
    series("Bản ghi xử lý / giờ", 8, 8, [target(
        'sum by (job, result) (increase(petcare_job_items_total{result!~"skipped"}[1h])) > 0', "{{job}} {{result}}")],
           h=10, bars=True),
    series("Thời gian chạy dài nhất", 16, 8, [target(
        f"max by (code_function) (tasks_scheduled_execution_seconds_max{{{APP}}})", "{{code_function}}")],
           unit="s", h=10, description="Từ tasks.scheduled.execution (Spring tự đo); job phải xong trong 60 s."),
]
advance(10)

# --- Rate limit + AI -------------------------------------------------------------------------------------------------
row("Rate limit và AI")
panels += [
    series("Request bị chặn 429 / phút", 0, 8, [target(
        f"sum by (group, window) (rate(petcare_ratelimit_rejected_total{{{APP}}}[5m])) * 60", "{{group}} · {{window}}")],
           description="RateLimitFilter (docs/adr/0100, 0110)."),
    stat("Redis rate limit mất kết nối (24 giờ)", 8, 4, [target(
        f"sum(increase(petcare_ratelimit_unavailable_total{{{APP}}}[24h])) or vector(0)", instant=True)],
         decimals=0, thresholds=RED, h=8,
         description="Mỗi lần mở circuit fail-open: trong 10 s đó login / register không bị giới hạn."),
    series("Chatbot theo kết quả / giờ", 12, 6, [target(
        f"sum by (result) (increase(ai_chat_result_total{{{APP}}}[1h]))", "{{result}}")], bars=True),
    series("Đồng bộ bài viết AI / giờ", 18, 6, [target(
        f"sum by (outcome) (increase(ai_article_sync_total{{{APP}}}[1h]))", "{{outcome}}")], bars=True),
]
advance(8)

# --- Nghiệp vụ -------------------------------------------------------------------------------------------------------
row("Nghiệp vụ theo chi nhánh (đếm từ lúc BE khởi động; tăng sau commit)")
panels += [
    stat("Doanh thu trong khoảng đang xem", 0, 6, [target(
        f"sum(increase(petcare_order_paid_amount_total{{{APP}}}[$__range])) or vector(0)", instant=True)],
         unit="currencyVND", decimals=0, h=6,
         description="Tổng Order PAID tại quầy (Order#8). Số chính xác lấy ở báo cáo BC; đây là số liệu vận hành."),
    stat("Order đã thu trong khoảng đang xem", 6, 6, [target(
        f"sum(increase(petcare_order_paid_total{{{APP}}}[$__range])) or vector(0)", instant=True)], decimals=0, h=6),
    stat("Lượt khám / dịch vụ hoàn tất", 12, 6, [target(
        f"sum(increase(petcare_visit_completed_total{{{APP}}}[$__range])) or vector(0)", instant=True)],
         decimals=0, h=6),
    stat("Lịch hẹn mới", 18, 6, [target(
        f"sum(increase(petcare_appointment_booked_total{{{APP}}}[$__range])) or vector(0)", instant=True)],
         decimals=0, h=6),
]
advance(6)
panels += [
    series("Lịch hẹn đặt / giờ", 0, 8, [target(
        f"sum by (branch, channel) (increase(petcare_appointment_booked_total{{{APP}}}[1h]))",
        "CN {{branch}} · {{channel}}")], bars=True),
    series("Tiếp nhận / giờ", 8, 8, [target(
        f"sum by (branch, kind) (increase(petcare_visit_checked_in_total{{{APP}}}[1h]))", "CN {{branch}} · {{kind}}")],
           bars=True),
    series("Doanh thu / giờ", 16, 8, [target(
        f"sum by (branch, source) (increase(petcare_order_paid_amount_total{{{APP}}}[1h]))",
        "CN {{branch}} · {{source}}")], unit="currencyVND", bars=True, stack=True),
]
advance(8)

dashboard = {
    "id": None,
    "uid": "petcare-overview",
    "title": "Pet Care · Tổng quan",
    "description": "Dashboard tự vẽ của Pet Care (docs/adr/0155). Sinh từ monitoring/grafana/tools/build_overview.py; sửa script rồi sinh lại.",
    "tags": ["petcare"],
    "timezone": "Asia/Ho_Chi_Minh",
    "editable": False,
    "graphTooltip": 1,
    "refresh": "30s",
    "schemaVersion": 39,
    "version": 1,
    "time": {"from": "now-6h", "to": "now"},
    "timepicker": {},
    "templating": {"list": []},
    "annotations": {"list": []},
    "links": [{"title": "Dashboard khác", "type": "dashboards", "tags": ["petcare"], "asDropdown": True}],
    "panels": panels,
}
DST.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print("wrote", DST, len(panels), "panels")
