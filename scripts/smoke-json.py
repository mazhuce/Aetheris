#!/usr/bin/env python3
"""Minimal JSON object builder/path reader used by smoke-test.sh."""

import json
import sys


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


if len(sys.argv) < 2:
    fail("usage: smoke-json.py object|get ...")

command = sys.argv[1]
if command == "object":
    values = sys.argv[2:]
    if len(values) % 2:
        fail("object requires key/value pairs")
    result = {}
    for index in range(0, len(values), 2):
        key_spec, raw = values[index], values[index + 1]
        key, separator, value_type = key_spec.partition(":")
        if not key:
            fail("object key cannot be empty")
        if separator and value_type == "int":
            result[key] = int(raw)
        elif separator and value_type == "bool":
            result[key] = raw.lower() == "true"
        elif separator:
            fail(f"unsupported value type: {value_type}")
        else:
            result[key] = raw
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
elif command == "get":
    if len(sys.argv) != 3:
        fail("get requires a dot-separated path")
    value = json.load(sys.stdin)
    for part in sys.argv[2].split("."):
        value = value[int(part)] if isinstance(value, list) else value[part]
    if isinstance(value, bool):
        print(str(value).lower())
    elif value is None:
        print("null")
    else:
        print(value)
else:
    fail(f"unknown command: {command}")
