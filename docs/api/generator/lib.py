"""Sinh docs/api/<module>-v1.md + docs/api/openapi/<module>-v1.yaml từ một khai báo duy nhất."""
import json
import os
import re

DOCS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # docs/api

# ---------------------------------------------------------------- YAML emitter
_SAFE = re.compile(r"^[A-Za-z_][A-Za-z0-9_./#$-]*$")
_KEYWORDS = {"true", "false", "null", "yes", "no", "on", "off", "y", "n", "~"}


def _scalar(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v)
    if _SAFE.match(s) and s.lower() not in _KEYWORDS:
        return s
    return json.dumps(s, ensure_ascii=False)


def _key(k):
    k = str(k)
    if re.match(r"^[A-Za-z_$][A-Za-z0-9_$-]*$", k) and k.lower() not in _KEYWORDS:
        return k
    return json.dumps(k, ensure_ascii=False)


def _flow_ok(lst):
    return all(not isinstance(x, (dict, list)) for x in lst)


def emit(obj, indent=0):
    pad = "  " * indent
    out = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, dict) and v:
                out.append(f"{pad}{_key(k)}:")
                out.extend(emit(v, indent + 1))
            elif isinstance(v, list) and v and not _flow_ok(v):
                out.append(f"{pad}{_key(k)}:")
                out.extend(emit(v, indent + 1))
            elif isinstance(v, list):
                out.append(f"{pad}{_key(k)}: [{', '.join(_scalar(x) for x in v)}]")
            elif isinstance(v, dict):
                out.append(f"{pad}{_key(k)}: {{}}")
            else:
                out.append(f"{pad}{_key(k)}: {_scalar(v)}")
    elif isinstance(obj, list):
        for item in obj:
            if isinstance(item, dict) and item:
                sub = emit(item, indent + 1)
                first = sub[0].lstrip()
                out.append(f"{pad}- {first}")
                out.extend(sub[1:])
            else:
                out.append(f"{pad}- {_scalar(item)}")
    return out


# ---------------------------------------------------------------- property DSL
def prop(spec):
    """'int64|mô tả', 'string:200?|…', 'enum:A,B|…', 'ref:X', 'array:ref:X', 'array:int64'."""
    typ, _, desc = spec.partition("|")
    nullable = typ.endswith("?")
    typ = typ.rstrip("?")
    s = _type(typ)
    if desc:
        s["description"] = desc
    if nullable and "type" in s:
        s["type"] = [s["type"], "null"]
        if "enum" in s:
            s["enum"] = s["enum"] + [None]
    return s


def _type(t):
    if t.startswith("array:"):
        return {"type": "array", "items": _type(t[6:])}
    if t.startswith("ref:"):
        return {"$ref": f"#/components/schemas/{t[4:]}"}
    if t.startswith("enum:"):
        return {"type": "string", "enum": t[5:].split(",")}
    if t.startswith("string:"):
        return {"type": "string", "maxLength": int(t[7:])}
    return {
        "int64": {"type": "integer", "format": "int64"},
        "int": {"type": "integer", "format": "int32"},
        "money": {"type": "integer", "format": "int64", "minimum": 0, "description": "VND"},
        "decimal": {"type": "number"},
        "string": {"type": "string"},
        "email": {"type": "string", "format": "email", "maxLength": 255},
        "phone": {"type": "string", "pattern": "^0[0-9]{9,10}$", "maxLength": 15},
        "date": {"type": "string", "format": "date"},
        "time": {"type": "string", "pattern": "^[0-2][0-9]:[0-5][0-9]$", "example": "08:30"},
        "datetime": {"type": "string", "format": "date-time"},
        "bool": {"type": "boolean"},
        "object": {"type": "object", "additionalProperties": True},
        "url": {"type": "string", "maxLength": 500},
    }[t]


# ---------------------------------------------------------------- module model
STATUS_TEXT = {200: "OK", 201: "Đã tạo", 202: "Đã nhận", 204: "Không có nội dung"}
ERR_DEFAULT = {
    400: "VALIDATION_FAILED / MALFORMED_REQUEST: dữ liệu không hợp lệ · BUSINESS_RULE_VIOLATION: vi phạm rule, message kết thúc bằng mã rule \"(BR-…)\".",
    401: "UNAUTHENTICATED: chưa đăng nhập hoặc phiên đã hết hạn / bị hủy.",
    403: "ACCESS_DENIED: sai vai trò · ACCESS_DENIED_SCOPE_MISMATCH: ngoài phạm vi (chi nhánh, chủ sở hữu). Message chung, không lộ cấu trúc phân quyền.",
    404: "Không tìm thấy (RESOURCE_NOT_FOUND).",
    409: "INVALID_STATE_TRANSITION: trạng thái hiện tại không cho phép thao tác · CONCURRENCY_CONFLICT: xung đột cập nhật đồng thời.",
}
ERR_REF = {400: "BadRequest", 401: "Unauthorized", 403: "Forbidden", 404: "NotFound", 409: "Conflict"}


class Op:
    def __init__(self, **kw):
        self.__dict__.update(kw)


class Module:
    def __init__(self, key, title, codes, owner, sources, intro, frozen, security=None, assumptions=None,
                 questions=None, tags=None):
        self.key, self.title, self.codes, self.owner = key, title, codes, owner
        self.sources, self.intro, self.frozen = sources, intro, frozen
        self.security = security or []
        self.assumptions = assumptions or []
        self.questions = questions or []
        self.tags = tags or {}
        self.schemas = {}
        self.params = {}
        self.ops = []

    # ---- declarations
    def schema(self, name, props, required=None, desc=None, enum=None):
        if enum is not None:
            s = {"type": "string", "enum": enum}
            if desc:
                s["description"] = desc
            self.schemas[name] = s
            return
        s = {"type": "object"}
        if desc:
            s["description"] = desc
        req = required if required is not None else [k for k, v in props.items() if not v.split("|")[0].endswith("?")]
        if req:
            s["required"] = req
        s["properties"] = {k: prop(v) for k, v in props.items()}
        self.schemas[name] = s

    def param(self, name, pname, where, spec, required=True):
        p = {"name": pname, "in": where, "required": required, "schema": _type(spec.split("|")[0])}
        if "|" in spec:
            p["description"] = spec.split("|", 1)[1]
        self.params[name] = p

    def op(self, method, path, op_id, name, tag, uc, rules, actor, authz, transition="—", body=None,
           resp=None, status=200, page=False, errors=(400, 401, 403, 404, 409), err_desc=None, query=None,
           path_params=None, notes="", public=False, idem="—"):
        self.ops.append(Op(method=method, path=path, op_id=op_id, name=name, tag=tag, uc=uc, rules=rules,
                           actor=actor, authz=authz, transition=transition, body=body, resp=resp, status=status,
                           page=page, errors=[e for e in errors
                                              if not (public and e in (401, 403) and e not in (err_desc or {}))],
                           err_desc=err_desc or {}, query=query or [], path_params=path_params or [],
                           notes=notes, public=public, idem=idem))

    # ---- OpenAPI
    def _response(self, op):
        if op.status == 204 or op.resp is None:
            return {"description": STATUS_TEXT.get(op.status, "OK")}
        data = {"$ref": f"#/components/schemas/{op.resp}"} if not op.resp.startswith("array:") else \
            {"type": "array", "items": {"$ref": f"#/components/schemas/{op.resp[6:]}"}}
        if op.page:
            data = {"allOf": [{"$ref": "#/components/schemas/PageMeta"}, {
                "type": "object", "required": ["content"],
                "properties": {"content": {"type": "array", "items": {"$ref": f"#/components/schemas/{op.resp}"}}}}]}
        return {"description": STATUS_TEXT.get(op.status, "OK"), "content": {"application/json": {"schema": {
            "type": "object", "required": ["data", "message", "code"],
            "properties": {"data": data, "message": {"type": "string"},
                           "code": {"type": "integer", "description": "HTTP status của kết quả, ví dụ 200, 201"}}}}}}

    def openapi(self):
        paths = {}
        for op in self.ops:
            o = {"tags": [op.tag], "summary": op.name,
                 "description": f"{op.uc} · {op.rules}. Actor: {op.actor}. Quyền: {op.authz}. Chuyển trạng thái: {op.transition}."
                                + (f" {op.notes}" if op.notes else ""),
                 "operationId": op.op_id}
            o["security"] = [] if op.public else [{"bearerAuth": []}]
            params = [{"$ref": f"#/components/parameters/{p}"} for p in op.path_params]
            for q in op.query:
                qn, _, qs = q.partition("=")
                req = qn.endswith("!")
                qn = qn.rstrip("!")
                if qn in ("page", "size"):
                    params.append({"$ref": f"#/components/parameters/{qn.capitalize()}"})
                    continue
                typ, _, d = qs.partition("|")
                pp = {"name": qn, "in": "query", "required": req, "schema": _type(typ)}
                if d:
                    pp["description"] = d
                params.append(pp)
            if params:
                o["parameters"] = params
            if op.body:
                o["requestBody"] = {"required": True, "content": {"application/json": {
                    "schema": {"$ref": f"#/components/schemas/{op.body}"}}}}
            responses = {str(op.status): self._response(op)}
            for e in op.errors:
                if e in op.err_desc:
                    responses[str(e)] = {"description": op.err_desc[e], "content": {"application/json": {
                        "schema": {"$ref": "#/components/schemas/Error"}}}}
                else:
                    responses[str(e)] = {"$ref": f"#/components/responses/{ERR_REF[e]}"}
            o["responses"] = responses
            paths.setdefault(op.path, {})[op.method] = o
        tags = [{"name": t, "description": d} for t, d in self.tags.items() if any(o.tag == t for o in self.ops)]
        used = json.dumps([paths, self.schemas])
        pick = lambda d, kind: {k: v for k, v in d.items() if f"#/components/{kind}/{k}\"" in used}
        comp = {
            "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer",
                                               "description": "Access token trả về từ POST /auth/login, gắn với một phiên (sessions). Phiên bị hủy thì token hết hiệu lực ngay (BR-TK-11, 13, 14)."}},
            "parameters": {**pick(COMMON_PARAMS, "parameters"), **self.params},
            "schemas": {**pick(COMMON_SCHEMAS, "schemas"), **self.schemas},
            "responses": pick(COMMON_RESPONSES, "responses"),
        }
        if "#/components/responses/" in used or "#/components/schemas/Error" in used:
            comp["schemas"].setdefault("Error", COMMON_SCHEMAS["Error"])
        if not comp["responses"]:
            del comp["responses"]
        return {"openapi": "3.1.0",
                "info": {"title": f"Pet Care {self.title}", "version": "1.0.0",
                         "description": f"Module {self.codes} · owner {self.owner}. Nguồn: {self.sources}. Chi tiết và truy vết: ../{self.key}-v1.md"},
                "servers": [{"url": "/api", "description": "Mọi path bên dưới nằm sau tiền tố /api"}],
                "tags": tags, "paths": paths, "components": comp}

    def write_yaml(self):
        header = [
            f"# Pet Care {self.title} — OpenAPI 3.1, sinh từ khai báo; không sửa tay, sửa khai báo rồi sinh lại.",
            f"# Module {self.codes} · owner {self.owner}",
            f"# Nguồn: {self.sources}",
            "# Truy vết: CONFIRMED = có trong docs · ASSUMPTION (A#) · TBD (Q#) — xem file .md cùng tên.",
            "",
        ]
        text = "\n".join(header + emit(self.openapi())) + "\n"
        os.makedirs(os.path.join(DOCS, "openapi"), exist_ok=True)
        with open(os.path.join(DOCS, "openapi", f"{self.key}-v1.yaml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)

    # ---- Markdown
    def _fields(self, name, depth=0):
        s = self.schemas.get(name) or COMMON_SCHEMAS.get(name)
        if not s or "properties" not in s:
            return f"`{name}`"
        req = set(s.get("required", []))
        parts = []
        for k, v in s["properties"].items():
            mark = "" if k in req else "?"
            if isinstance(v.get("type"), list):
                v = {**v, "type": v["type"][0]}
                if "enum" in v:
                    v["enum"] = [e for e in v["enum"] if e is not None]
            t = v.get("$ref", "").split("/")[-1] or (
                "[" + (v["items"].get("$ref", "").split("/")[-1] or v["items"].get("type", "")) + "]"
                if v.get("type") == "array" else ("|".join(v["enum"]) if "enum" in v else v.get("format", v.get("type"))))
            parts.append(f"`{k}{mark}`: {t}")
        return "{" + ", ".join(parts) + "}"

    def write_md(self):
        L = []
        L.append(f"# {self.title} — Pet Care v16\n")
        L.append(f"> **Module:** {self.codes} · **Owner:** {self.owner}. {self.intro}")
        L.append(f"> **Nguồn chân lý:** {self.sources}.")
        L.append(f"> **Contract máy đọc:** [`./openapi/{self.key}-v1.yaml`](./openapi/{self.key}-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.\n")
        L.append("**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · "
                 "`TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở "
                 "[`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.\n")
        if self.frozen:
            L.append("**Đóng băng phạm vi (không có endpoint):**")
            for x in self.frozen:
                L.append(f"- {x}")
            L.append("")
        L.append("---\n")
        L.append(f"## A. Danh sách endpoint ({len(self.ops)})\n")
        L.append("| # | Endpoint | Thao tác | Use case | Rule |")
        L.append("|---|---|---|---|---|")
        for i, op in enumerate(self.ops, 1):
            L.append(f"| {i} | `{op.method.upper()} {op.path}` | {op.name} | {op.uc} | {op.rules} |")
        L.append("\n---\n")
        L.append("## B. Ma trận thiết kế\n")
        L.append("| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |")
        L.append("|---|---|---|---|---|---|")
        for op in self.ops:
            L.append(f"| {op.name} | {op.actor} | {op.authz} | {op.transition} | {op.idem} | {op.notes or '—'} |")
        L.append("\n---\n")
        L.append("## C. Chi tiết endpoint\n")
        cur = None
        for op in self.ops:
            if op.tag != cur:
                cur = op.tag
                L.append(f"### {cur}\n")
            if op.resp and op.resp.startswith("array:"):
                resp = f"mảng `{op.resp[6:]}`"
            elif op.resp:
                resp = ("trang `" if op.page else "`") + op.resp + "`"
            else:
                resp = "rỗng"
            errs = " · ".join(f"`{e}`" + (" " + op.err_desc[e] if e in op.err_desc else "") for e in op.errors)
            L.append(f"- **`{op.method.upper()} {op.path}`** — {op.name}. "
                     + ("Public. " if op.public else "")
                     + (f"Query: {', '.join('`' + q.split('=')[0].rstrip('!') + ('' if q.split('=')[0].endswith('!') else '?') + '`' for q in op.query)}. " if op.query else "")
                     + (f"Request `{op.body}`. " if op.body else "")
                     + f"Response `{op.status}` {resp}."
                     + (f" Lỗi: {errs}." if errs else ""))
        L.append("\n### Schema\n")
        L.append("Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.\n")
        for name, s in self.schemas.items():
            if "enum" in s:
                L.append(f"- **`{name}`** — enum: {', '.join('`' + str(e) + '`' for e in s['enum'])}." + (f" {s['description']}" if s.get("description") else ""))
            else:
                L.append(f"- **`{name}`** — {self._fields(name)}" + (f" — {s['description']}" if s.get("description") else ""))
        L.append("\n---\n")
        L.append("## D. Bảo mật & độ tin cậy\n")
        for i, x in enumerate(self.security, 1):
            L.append(f"{i}. {x}")
        L.append("\n---\n")
        L.append("## E. Giả định & câu hỏi mở\n")
        if self.assumptions:
            L.append("| ID | Giả định | Lý do |")
            L.append("|---|---|---|")
            for a in self.assumptions:
                L.append(f"| {a[0]} | {a[1]} | {a[2]} |")
            L.append("")
        if self.questions:
            L.append("| ID | Câu hỏi | Trạng thái | Gốc |")
            L.append("|---|---|---|---|")
            for q in self.questions:
                L.append(f"| {q[0]} | {q[1]} | {q[2]} | {q[3]} |")
        else:
            L.append("Không có câu hỏi mở.")
        L.append("\n---\n")
        L.append(f"## F. OpenAPI 3.1\n\n[`./openapi/{self.key}-v1.yaml`](./openapi/{self.key}-v1.yaml)\n")
        with open(os.path.join(DOCS, f"{self.key}-v1.md"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(L))

    def write(self):
        ids = [o.op_id for o in self.ops]
        dup = {i for i in ids if ids.count(i) > 1}
        assert not dup, dup
        for o in self.ops:
            for name in (o.body, o.resp[6:] if o.resp and o.resp.startswith("array:") else o.resp):
                if name:
                    assert name in self.schemas or name in COMMON_SCHEMAS, (self.key, o.op_id, name)
            for p in o.path_params:
                assert p in self.params or p in COMMON_PARAMS, (self.key, o.op_id, p)
            for seg in re.findall(r"{(\w+)}", o.path):
                assert any((self.params.get(p) or COMMON_PARAMS.get(p))["name"] == seg for p in o.path_params), (o.op_id, seg)
        self.write_yaml()
        self.write_md()
        return len(self.ops)


# ---------------------------------------------------------------- shared components
COMMON_PARAMS = {
    "Page": {"name": "page", "in": "query", "required": False, "schema": {"type": "integer", "minimum": 0, "default": 0},
             "description": "Số trang, bắt đầu từ 0"},
    "Size": {"name": "size", "in": "query", "required": False,
             "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
}
COMMON_SCHEMAS = {
    "Error": {
        "type": "object",
        "description": "Envelope lỗi duy nhất, do GlobalExceptionHandler (platform/exception) sinh. Vi phạm business rule: errorCode = BUSINESS_RULE_VIOLATION, message kết thúc bằng mã rule, ví dụ \"… (BR-LH-05)\".",
        "required": ["success", "errorCode", "message", "statusCode", "timestamp", "traceId"],
        "properties": {
            "success": {"type": "boolean", "enum": [False]},
            "errorCode": {"type": "string", "enum": ["BUSINESS_RULE_VIOLATION", "INVALID_STATE_TRANSITION",
                                                     "RESOURCE_NOT_FOUND", "ACCESS_DENIED_SCOPE_MISMATCH",
                                                     "CONCURRENCY_CONFLICT", "VALIDATION_FAILED", "MALFORMED_REQUEST",
                                                     "METHOD_NOT_ALLOWED", "UNSUPPORTED_MEDIA_TYPE", "UNAUTHENTICATED",
                                                     "ACCESS_DENIED", "INTERNAL_ERROR"],
                          "description": "platform/exception/ErrorCode"},
            "message": {"type": "string", "description": "Thông điệp tiếng Việt hiển thị được; với BUSINESS_RULE_VIOLATION kết thúc bằng \" (BR-…)\""},
            "statusCode": {"type": "integer"},
            "timestamp": {"type": "string", "format": "date-time"},
            "traceId": {"type": "string"},
        },
    },
    "PageMeta": {
        "type": "object",
        "required": ["page", "size", "totalElements", "totalPages"],
        "properties": {
            "page": {"type": "integer"}, "size": {"type": "integer"},
            "totalElements": {"type": "integer", "format": "int64"}, "totalPages": {"type": "integer"},
        },
    },
}
COMMON_RESPONSES = {
    ERR_REF[c]: {"description": ERR_DEFAULT[c], "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}}
    for c in ERR_REF
}
