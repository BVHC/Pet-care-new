"""Chuyển dashboard cộng đồng (grafana.com) sang dạng provisioning: datasource cố định, uid cố định, bỏ __inputs.

Cập nhật bản mới (docs/adr/0155): tải https://grafana.com/api/dashboards/<id>/revisions/latest/download thành
<thư mục tải>/<id>.json cho 4701, 19004, 9628, 763 rồi chạy
python monitoring/grafana/tools/prepare_dashboards.py <thư mục tải> monitoring/grafana/dashboards
Sau đó ẩn biến Namespace của spring-boot.json (hide = 2): compose không có nhãn namespace.
"""
import json
import re
import sys
from pathlib import Path

SRC = Path(sys.argv[1])
DST = Path(sys.argv[2])
DS_VAR = re.compile(r"^\$\{DS_[A-Z_]+\}$")

DASHBOARDS = {
    "4701": ("petcare-jvm", "jvm-micrometer.json", "Pet Care · JVM (Micrometer)"),
    "19004": ("petcare-spring-boot", "spring-boot.json", "Pet Care · Spring Boot 3.x"),
    "9628": ("petcare-postgres", "postgresql.json", "Pet Care · PostgreSQL"),
    "763": ("petcare-redis", "redis.json", "Pet Care · Redis"),
}


def walk(node, parent_key=None):
    if isinstance(node, dict):
        return {k: walk(v, k) for k, v in node.items()}
    if isinstance(node, list):
        return [walk(v, parent_key) for v in node]
    if isinstance(node, str) and DS_VAR.match(node):
        return "prometheus" if parent_key == "uid" else "Prometheus"
    if isinstance(node, str) and "${DS_" in node:
        return re.sub(r"\$\{DS_[A-Z_]+\}", "Prometheus", node)
    return node


for source_id, (uid, filename, title) in DASHBOARDS.items():
    data = json.loads((SRC / f"{source_id}.json").read_text(encoding="utf-8"))
    for key in ("__inputs", "__requires", "__elements"):
        data.pop(key, None)
    data = walk(data)
    data["id"] = None
    data["uid"] = uid
    data["title"] = title
    data["description"] = (f"Từ grafana.com/dashboards/{source_id} (bản mới nhất lúc tải 25/11/2026). "
                           "Datasource cố định 'Prometheus' (docs/adr/0155).")
    data["tags"] = sorted(set(data.get("tags", [])) | {"petcare"})
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    assert "${DS_" not in text, filename
    (DST / filename).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", filename, len(text))
