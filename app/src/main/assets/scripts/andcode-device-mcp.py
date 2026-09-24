#!/usr/bin/env python3
"""AndCode Android Device MCP server (stdlib only).

Bridges the AI agent running inside the embedded Linux guest to native Android
operating system capabilities (Apps, Calling, WhatsApp, SMS, Media Search,
Timers, Delayed Scheduling, Hardware Controls, and Accessibility Automation).

This server communicates via a request/response file bridge at:
    /workspace/.and-code/device-bridge
The Android app polls the pending/ directory, executes the action on the Android
main thread/services, and drops the result into responses/.
"""

import json
import os
import sys
import time
import uuid
from pathlib import Path

BRIDGE_ROOT = Path("/workspace/.and-code/device-bridge")
PENDING_DIR = BRIDGE_ROOT / "pending"
RESPONSES_DIR = BRIDGE_ROOT / "responses"

RESPONSE_TIMEOUT_S = 45
POLL_INTERVAL_S = 0.25


class BridgeError(RuntimeError):
    pass


def _write_atomic(file: Path, payload: dict) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    tmp = file.with_name(file.name + ".tmp")
    tmp.write_text(json.dumps(payload), encoding="utf-8")
    os.replace(tmp, file)


def _call(op: str, args: dict) -> str:
    """Writes a request for the Android app and waits for its reply."""
    PENDING_DIR.mkdir(parents=True, exist_ok=True)
    request_id = uuid.uuid4().hex
    _write_atomic(
        PENDING_DIR / f"{request_id}.json",
        {
            "op": op,
            "args": args or {},
            "createdAtMs": int(time.time() * 1000),
        },
    )
    response_file = RESPONSES_DIR / f"{request_id}.json"
    deadline = time.monotonic() + RESPONSE_TIMEOUT_S
    while time.monotonic() < deadline:
        if response_file.is_file():
            try:
                payload = json.loads(response_file.read_text(encoding="utf-8"))
            except Exception:
                time.sleep(0.1)
                continue
            try:
                response_file.unlink()
            except OSError:
                pass
            if payload.get("ok"):
                return json.dumps(payload.get("data"), ensure_ascii=False, indent=2)
            raise BridgeError(payload.get("error") or "The Android device rejected the request")
        time.sleep(POLL_INTERVAL_S)
    raise BridgeError(f"No reply from Android device within {RESPONSE_TIMEOUT_S} s")


def tool_launch_app(args: dict) -> str:
    return _call("launch_app", args)


def tool_make_call(args: dict) -> str:
    return _call("make_call", args)


def tool_search_contacts(args: dict) -> str:
    return _call("search_contacts", args)


def tool_send_whatsapp(args: dict) -> str:
    return _call("send_whatsapp", args)


def tool_send_sms(args: dict) -> str:
    return _call("send_sms", args)


def tool_search_media(args: dict) -> str:
    return _call("search_media", args)


def tool_open_file(args: dict) -> str:
    return _call("open_file", args)


def tool_set_timer(args: dict) -> str:
    return _call("set_timer", args)


def tool_schedule_action(args: dict) -> str:
    return _call("schedule_action", args)


def tool_control_hardware(args: dict) -> str:
    return _call("control_hardware", args)


def tool_get_status(args: dict) -> str:
    return _call("get_device_status", args)


def tool_screen_click(args: dict) -> str:
    return _call("accessibility_click", args)


def tool_screen_type(args: dict) -> str:
    return _call("accessibility_type", args)


def tool_screen_dump(args: dict) -> str:
    return _call("accessibility_dump_screen", args)


TOOLS = [
    {
        "name": "device_launch_app",
        "description": "Launch any installed application on the phone by its name or package (e.g. 'whatsapp', 'واتساب', 'youtube', 'يوتيوب', 'camera', 'settings', etc.).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "appName": {"type": "string", "description": "The name or package name of the app to launch"}
            },
            "required": ["appName"],
        },
    },
    {
        "name": "device_make_call",
        "description": "Make a phone call to a contact name or phone number, or open the dialer.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "recipient": {"type": "string", "description": "Contact name or phone number to call"},
                "directCall": {"type": "boolean", "description": "Whether to place the call directly or open the dialer (default true)"}
            },
            "required": ["recipient"],
        },
    },
    {
        "name": "device_send_whatsapp",
        "description": "Send or compose a WhatsApp message to a contact or phone number. Supports auto-clicking the send button if Accessibility is active.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "recipient": {"type": "string", "description": "Contact name or phone number"},
                "message": {"type": "string", "description": "The message text to send"},
                "autoClickSend": {"type": "boolean", "description": "Automatically tap send in WhatsApp (default true)"}
            },
            "required": ["recipient", "message"],
        },
    },
    {
        "name": "device_send_sms",
        "description": "Send an SMS text message to a contact or phone number.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "recipient": {"type": "string", "description": "Contact name or phone number"},
                "message": {"type": "string", "description": "The SMS text"}
            },
            "required": ["recipient", "message"],
        },
    },
    {
        "name": "device_search_media",
        "description": "Search the phone's storage for specific files, videos, photos, or documents by name/keyword.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query or keyword in filename"},
                "mediaType": {"type": "string", "description": "Optional filter: 'video', 'image', 'audio', or 'document'"},
                "limit": {"type": "integer", "description": "Maximum number of results to return (default 15)"}
            },
            "required": ["query"],
        },
    },
    {
        "name": "device_open_file",
        "description": "Open a file on the Android phone with its default viewer (e.g. video player, photo viewer, document reader).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Absolute file path on the device (e.g. /sdcard/Download/video.mp4)"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "device_set_timer",
        "description": "Set a countdown timer in seconds using the system clock.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "seconds": {"type": "integer", "description": "Duration of timer in seconds"},
                "label": {"type": "string", "description": "Label/name for the timer"}
            },
            "required": ["seconds"],
        },
    },
    {
        "name": "device_schedule_action",
        "description": "Schedule a delayed phone action (e.g., 'send a WhatsApp message after 10 minutes to John').",
        "inputSchema": {
            "type": "object",
            "properties": {
                "delayMinutes": {"type": "integer", "description": "Delay before executing the action, in minutes"},
                "actionType": {"type": "string", "description": "Type of action: 'send_whatsapp', 'send_sms', 'make_call', 'reminder'"},
                "parameters": {"type": "object", "description": "Parameters for the action, e.g. {'recipient': 'John', 'message': 'Hello'}"}
            },
            "required": ["delayMinutes", "actionType"],
        },
    },
    {
        "name": "device_control_hardware",
        "description": "Control device hardware features (e.g., flashlight / torch).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "feature": {"type": "string", "description": "Hardware feature name, e.g. 'flashlight'"},
                "state": {"type": "boolean", "description": "True to turn on, False to turn off"}
            },
            "required": ["feature", "state"],
        },
    },
    {
        "name": "device_get_status",
        "description": "Get current device telemetry: battery percentage, charging state, available storage space, time, and device model.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "device_screen_click",
        "description": "Click any button or text on screen via Accessibility Service.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {"type": "string", "description": "Text, content description, or resource-id of the element to click"}
            },
            "required": ["target"],
        },
    },
    {
        "name": "device_screen_type",
        "description": "Type text into the currently active input field on the phone screen.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "The text to type"}
            },
            "required": ["text"],
        },
    },
    {
        "name": "device_screen_dump",
        "description": "Inspect the currently visible screen content, buttons, and elements.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
]

HANDLERS = {
    "device_launch_app": tool_launch_app,
    "device_make_call": tool_make_call,
    "device_send_whatsapp": tool_send_whatsapp,
    "device_send_sms": tool_send_sms,
    "device_search_media": tool_search_media,
    "device_open_file": tool_open_file,
    "device_set_timer": tool_set_timer,
    "device_schedule_action": tool_schedule_action,
    "device_control_hardware": tool_control_hardware,
    "device_get_status": tool_get_status,
    "device_screen_click": tool_screen_click,
    "device_screen_type": tool_screen_type,
    "device_screen_dump": tool_screen_dump,
}


def respond(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "result": {
                        "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "and-code-device", "version": "1.0.0"},
                    },
                }
            )
        elif method in ("notifications/initialized", "initialized"):
            continue
        elif method == "tools/list":
            respond({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {}) or {}
            handler = HANDLERS.get(name)
            if handler is None:
                respond(
                    {
                        "jsonrpc": "2.0",
                        "id": mid,
                        "result": {
                            "content": [{"type": "text", "text": f"unknown tool: {name}"}],
                            "isError": True,
                        },
                    }
                )
            else:
                try:
                    text = handler(args)
                    respond(
                        {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}]}}
                    )
                except Exception as exc:
                    respond(
                        {
                            "jsonrpc": "2.0",
                            "id": mid,
                            "result": {
                                "content": [{"type": "text", "text": f"{type(exc).__name__}: {exc}"}],
                                "isError": True,
                            },
                        }
                    )
        elif mid is not None:
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "error": {"code": -32601, "message": f"method not found: {method}"},
                }
            )


if __name__ == "__main__":
    main()
