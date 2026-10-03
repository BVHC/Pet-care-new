"""Sinh lại docs/api/<module>-v1.md và docs/api/openapi/<module>-v1.yaml.

Chạy:  python docs/api/generator/generate.py            (mọi module)
       python docs/api/generator/generate.py visit sales (một vài module)
Sau đó: node docs/api/check-contracts.mjs
"""
import importlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
MODULES = ["identity", "customer", "catalog", "branch", "appointment", "visit", "sales", "inventory", "boarding",
           "content", "care", "report"]

total = 0
for key in sys.argv[1:] or MODULES:
    n = importlib.import_module(f"m_{key}").m.write()
    print(f"{key}: {n} endpoints")
    total += n
print(f"total: {total} endpoints")
