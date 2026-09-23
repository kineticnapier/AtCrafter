from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from atcoder import AtCoderError, is_remote_problem_id, list_default_problems, load_remote_problem
from atcoder_submit import AtCoderSubmitError, session_status, submit_code

HOST = "127.0.0.1"
PORT = 8765
MAX_REQUEST_BYTES = 2 * 1024 * 1024
MAX_OUTPUT_CHARS = 1_000_000
DEFAULT_TIMEOUT_MS = 2_000
MAX_TIMEOUT_MS = 10_000
ROOT_DIR = Path(__file__).resolve().parent.parent
PROBLEMS_DIR = ROOT_DIR / "problems"

DEBUG_DRIVER = r'''
import ast
import io
import json
import math
import operator
import sys
import traceback
import types
from collections import deque

TARGET = sys.argv[1]
TRACE_FILE = sys.argv[2]
MAX_STEPS = 5000
MAX_VALUE_CHARS = 240
MAX_STEP_STDOUT_CHARS = 4096
MAX_COLLECTION_ITEMS = 32
MAX_DEPTH = 2
steps = []
trace_truncated = False
last_stdout_snapshot = None
access_specs = {}
UNRESOLVED = object()

IGNORED_LOCAL_TYPES = (
    types.ModuleType,
    types.FunctionType,
    types.BuiltinFunctionType,
    types.MethodType,
    type,
)

SAFE_BINOPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
}


class TeeStdout:
    def __init__(self, target):
        self.target = target
        self.buffer = io.StringIO()

    def write(self, text):
        self.buffer.write(text)
        return self.target.write(text)

    def flush(self):
        self.target.flush()

    def isatty(self):
        return False

    @property
    def encoding(self):
        return getattr(self.target, "encoding", "utf-8")

    def snapshot(self):
        text = self.buffer.getvalue()
        if len(text) <= MAX_STEP_STDOUT_CHARS:
            return text
        return "...<stdout truncated>\n" + text[-MAX_STEP_STDOUT_CHARS:]


def safe_repr(value):
    try:
        text = repr(value)
    except Exception as error:
        text = f"<repr failed: {type(error).__name__}>"
    if len(text) > MAX_VALUE_CHARS:
        return text[: MAX_VALUE_CHARS - 3] + "..."
    return text


def encode_value(value, depth=0):
    display = safe_repr(value)

    if value is None:
        return {"type": "none", "display": display}
    if type(value) is bool:
        return {"type": "bool", "display": display, "boolValue": value}
    if type(value) is int:
        return {"type": "int", "display": display, "numberText": str(value)}
    if type(value) is float:
        number_text = str(value) if math.isfinite(value) else display
        return {"type": "float", "display": display, "numberText": number_text}
    if type(value) is str:
        text = value
        if len(text) > MAX_VALUE_CHARS:
            text = text[: MAX_VALUE_CHARS - 3] + "..."
        return {"type": "str", "display": display, "text": text}

    if isinstance(value, (list, tuple, set, frozenset, deque)):
        type_name = type(value).__name__
        if depth >= MAX_DEPTH:
            return {"type": type_name, "display": display, "items": [], "truncated": True}
        try:
            if isinstance(value, (set, frozenset)):
                sequence = sorted(value, key=safe_repr)
            else:
                sequence = list(value)
        except Exception:
            return {"type": type_name, "display": display, "items": [], "truncated": True}
        items = [encode_value(item, depth + 1) for item in sequence[:MAX_COLLECTION_ITEMS]]
        return {
            "type": type_name,
            "display": display,
            "items": items,
            "truncated": len(sequence) > MAX_COLLECTION_ITEMS,
        }

    if isinstance(value, dict):
        if depth >= MAX_DEPTH:
            return {"type": "dict", "display": display, "entries": [], "truncated": True}
        entries = []
        try:
            source = list(value.items())
        except Exception:
            source = []
        for key, item in source[:MAX_COLLECTION_ITEMS]:
            entries.append({
                "key": encode_value(key, depth + 1),
                "value": encode_value(item, depth + 1),
            })
        return {
            "type": "dict",
            "display": display,
            "entries": entries,
            "truncated": len(source) > MAX_COLLECTION_ITEMS,
        }

    return {
        "type": "object",
        "display": display,
        "className": type(value).__name__,
    }


def snapshot(frame):
    result = {}
    for name, value in frame.f_locals.items():
        if name.startswith("__") or isinstance(value, IGNORED_LOCAL_TYPES):
            continue
        result[str(name)] = encode_value(value)
    return result


def add_access_spec(line, kind, variable, index_node):
    access_specs.setdefault(line, []).append((kind, variable, index_node))


class AccessCollector(ast.NodeVisitor):
    def visit_Subscript(self, node):
        if isinstance(node.value, ast.Name):
            if isinstance(node.ctx, ast.Store):
                add_access_spec(node.lineno, "write", node.value.id, node.slice)
            elif isinstance(node.ctx, ast.Load):
                add_access_spec(node.lineno, "read", node.value.id, node.slice)
        self.generic_visit(node)

    def visit_AugAssign(self, node):
        target = node.target
        if isinstance(target, ast.Subscript) and isinstance(target.value, ast.Name):
            add_access_spec(target.lineno, "read", target.value.id, target.slice)
        self.generic_visit(node)


def lookup_name(frame, name):
    if name in frame.f_locals:
        return frame.f_locals[name]
    if name in frame.f_globals:
        return frame.f_globals[name]
    return UNRESOLVED


def resolve_index_node(node, frame):
    if isinstance(node, ast.Constant) and type(node.value) is int:
        return node.value
    if isinstance(node, ast.Name):
        value = lookup_name(frame, node.id)
        return value if type(value) is int else UNRESOLVED
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
        operand = resolve_index_node(node.operand, frame)
        if type(operand) is not int:
            return UNRESOLVED
        return operand if isinstance(node.op, ast.UAdd) else -operand
    if isinstance(node, ast.BinOp):
        operation = SAFE_BINOPS.get(type(node.op))
        if operation is None:
            return UNRESOLVED
        left = resolve_index_node(node.left, frame)
        right = resolve_index_node(node.right, frame)
        if type(left) is not int or type(right) is not int:
            return UNRESOLVED
        try:
            result = operation(left, right)
        except Exception:
            return UNRESOLVED
        return result if type(result) is int else UNRESOLVED
    return UNRESOLVED


def resolve_accesses(frame, line):
    result = []
    seen = set()
    for kind, variable, index_node in access_specs.get(line, []):
        index = resolve_index_node(index_node, frame)
        if type(index) is not int:
            continue

        container = lookup_name(frame, variable)
        if container is not UNRESOLVED and index < 0:
            try:
                index += len(container)
            except Exception:
                pass

        key = (kind, variable, index)
        if key in seen:
            continue
        seen.add(key)
        result.append({"kind": kind, "variable": variable, "index": index})
    return result


original_stdout = sys.stdout
captured_stdout = TeeStdout(original_stdout)
sys.stdout = captured_stdout


def tracer(frame, event, arg):
    global trace_truncated, last_stdout_snapshot
    if frame.f_code.co_filename == TARGET and event in ("line", "return"):
        if len(steps) < MAX_STEPS:
            step = {
                "line": frame.f_lineno,
                "event": event,
                "locals": snapshot(frame),
            }
            if event == "line":
                accesses = resolve_accesses(frame, frame.f_lineno)
                if accesses:
                    step["accesses"] = accesses
            stdout_snapshot = captured_stdout.snapshot()
            if stdout_snapshot != last_stdout_snapshot:
                step["stdout"] = stdout_snapshot
                last_stdout_snapshot = stdout_snapshot
            steps.append(step)
        else:
            trace_truncated = True
    return tracer


namespace = {"__name__": "__main__", "__file__": TARGET}
try:
    source = open(TARGET, "r", encoding="utf-8").read()
    tree = ast.parse(source, TARGET, "exec")
    AccessCollector().visit(tree)
    compiled = compile(source, TARGET, "exec")
    sys.settrace(tracer)
    exec(compiled, namespace, namespace)
except BaseException:
    traceback.print_exc()
finally:
    sys.settrace(None)
    sys.stdout = original_stdout
    try:
        with open(TRACE_FILE, "w", encoding="utf-8") as trace_file:
            json.dump(
                {"steps": steps, "traceTruncated": trace_truncated},
                trace_file,
                ensure_ascii=False,
                separators=(",", ":"),
            )
    except Exception:
        traceback.print_exc()
'''


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def clamp_timeout(value: Any) -> int:
    if not isinstance(value, int):
        return DEFAULT_TIMEOUT_MS
    return max(100, min(value, MAX_TIMEOUT_MS))


def truncate(text: str) -> tuple[str, bool]:
    if len(text) <= MAX_OUTPUT_CHARS:
        return text, False
    return text[:MAX_OUTPUT_CHARS], True


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def read_test_cases(directory: Path, prefix: str) -> list[dict[str, str]]:
    if not directory.is_dir():
        return []

    cases: list[dict[str, str]] = []
    for input_path in sorted(directory.glob("*.in")):
        output_path = input_path.with_suffix(".out")
        if not output_path.is_file():
            continue
        cases.append(
            {
                "name": f"{prefix} {input_path.stem}",
                "stdin": read_text(input_path),
                "expected": read_text(output_path),
            }
        )
    return cases


def problem_dirs() -> list[Path]:
    if not PROBLEMS_DIR.is_dir():
        return []
    return sorted(
        (path for path in PROBLEMS_DIR.iterdir() if path.is_dir() and (path / "problem.json").is_file()),
        key=lambda path: path.name,
    )


def load_problem(problem_id: str) -> dict[str, Any] | None:
    if is_remote_problem_id(problem_id):
        try:
            return load_remote_problem(problem_id)
        except AtCoderError as error:
            print(f"AtCoder problem load failed: {error}")
            return None

    if not problem_id or problem_id in {".", ".."} or "/" in problem_id or "\\" in problem_id:
        return None

    directory = PROBLEMS_DIR / problem_id
    if not directory.is_dir() or directory.parent.resolve() != PROBLEMS_DIR.resolve():
        return None

    metadata_path = directory / "problem.json"
    if not metadata_path.is_file():
        return None

    try:
        metadata = json.loads(read_text(metadata_path))
    except (OSError, json.JSONDecodeError):
        return None

    title = metadata.get("title", problem_id)
    default_code = metadata.get("defaultCode", "")
    if not isinstance(title, str) or not isinstance(default_code, str):
        return None

    statement_path = directory / "statement.md"
    statement = read_text(statement_path) if statement_path.is_file() else ""

    return {
        "id": problem_id,
        "title": title,
        "statement": statement,
        "defaultCode": default_code,
        "samples": read_test_cases(directory / "samples", "Sample"),
        "tests": read_test_cases(directory / "tests", "Test"),
    }


def list_problems() -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    for directory in problem_dirs():
        problem = load_problem(directory.name)
        if problem is not None:
            result.append({"id": problem["id"], "title": problem["title"]})

    try:
        result.extend(list_default_problems())
    except AtCoderError as error:
        # AtCoder is an optional online source. Local problems must continue to work offline.
        print(f"AtCoder problem list unavailable: {error}")
    return result


def make_execution_result(
    process: subprocess.CompletedProcess[str],
    started: float,
) -> dict[str, Any]:
    stdout, stdout_truncated = truncate(process.stdout)
    stderr, stderr_truncated = truncate(process.stderr)
    return {
        "status": "finished",
        "exitCode": process.returncode,
        "stdout": stdout,
        "stderr": stderr,
        "elapsedMs": round((time.perf_counter() - started) * 1000, 3),
        "timedOut": False,
        "outputTruncated": stdout_truncated or stderr_truncated,
    }


def make_timeout_result(error: subprocess.TimeoutExpired, started: float) -> dict[str, Any]:
    stdout = error.stdout or ""
    stderr = error.stderr or ""
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    stdout, stdout_truncated = truncate(stdout)
    stderr, stderr_truncated = truncate(stderr)
    return {
        "status": "timed_out",
        "exitCode": None,
        "stdout": stdout,
        "stderr": stderr,
        "elapsedMs": round((time.perf_counter() - started) * 1000, 3),
        "timedOut": True,
        "outputTruncated": stdout_truncated or stderr_truncated,
    }


def execution_env() -> dict[str, str]:
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    return env


def run_python(code: str, stdin: str, timeout_ms: int) -> dict[str, Any]:
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix="atcrafter-") as temp_dir:
        script_path = os.path.join(temp_dir, "main.py")
        with open(script_path, "w", encoding="utf-8", newline="\n") as script:
            script.write(code)

        try:
            process = subprocess.run(
                [sys.executable, script_path],
                input=stdin,
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=temp_dir,
                env=execution_env(),
                timeout=timeout_ms / 1000.0,
                check=False,
            )
            return make_execution_result(process, started)
        except subprocess.TimeoutExpired as error:
            return make_timeout_result(error, started)


def debug_python(code: str, stdin: str, timeout_ms: int) -> dict[str, Any]:
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix="atcrafter-debug-") as temp_dir:
        script_path = os.path.join(temp_dir, "main.py")
        driver_path = os.path.join(temp_dir, "debug_driver.py")
        trace_path = os.path.join(temp_dir, "trace.json")

        with open(script_path, "w", encoding="utf-8", newline="\n") as script:
            script.write(code)
        with open(driver_path, "w", encoding="utf-8", newline="\n") as driver:
            driver.write(DEBUG_DRIVER)

        try:
            process = subprocess.run(
                [sys.executable, driver_path, script_path, trace_path],
                input=stdin,
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=temp_dir,
                env=execution_env(),
                timeout=timeout_ms / 1000.0,
                check=False,
            )
            result = make_execution_result(process, started)
        except subprocess.TimeoutExpired as error:
            result = make_timeout_result(error, started)

        trace_data: dict[str, Any] = {"steps": [], "traceTruncated": False}
        try:
            if os.path.isfile(trace_path):
                loaded = json.loads(Path(trace_path).read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    trace_data = loaded
        except (OSError, json.JSONDecodeError):
            pass

        steps = trace_data.get("steps", [])
        result["steps"] = steps if isinstance(steps, list) else []
        result["traceTruncated"] = bool(trace_data.get("traceTruncated", False))
        return result


class RunnerHandler(BaseHTTPRequestHandler):
    server_version = "AtCrafterRunner/0.10"

    def log_message(self, format: str, *args: object) -> None:
        print(f"[{self.log_date_time_string()}] {format % args}")

    def send_json(self, status: int, value: Any) -> None:
        body = json_bytes(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(
                200,
                {
                    "status": "ok",
                    "runnerVersion": "0.10",
                    "python": sys.version.split()[0],
                    "problemCount": len(list_problems()),
                },
            )
            return

        if self.path == "/atcoder/session":
            self.send_json(200, session_status())
            return

        if self.path == "/problems":
            self.send_json(200, {"problems": list_problems()})
            return

        if self.path.startswith("/problems/"):
            problem_id = unquote(self.path[len("/problems/") :])
            problem = load_problem(problem_id)
            if problem is None:
                self.send_json(404, {"error": "problem_not_found"})
            else:
                self.send_json(200, problem)
            return

        self.send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path not in {"/run", "/debug", "/atcoder/submit"}:
            self.send_json(404, {"error": "not_found"})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "invalid_content_length"})
            return

        if content_length <= 0 or content_length > MAX_REQUEST_BYTES:
            self.send_json(413, {"error": "request_too_large"})
            return

        try:
            payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_json(400, {"error": "invalid_json"})
            return

        if not isinstance(payload, dict):
            self.send_json(400, {"error": "invalid_json_object"})
            return

        if self.path == "/atcoder/submit":
            problem_id = payload.get("problemId")
            code = payload.get("code")
            if not isinstance(problem_id, str) or not isinstance(code, str):
                self.send_json(400, {"error": "problem_id_and_code_must_be_strings"})
                return
            try:
                result = submit_code(problem_id, code)
            except AtCoderSubmitError as error:
                self.send_json(
                    400,
                    {
                        "error": "atcoder_submit_failed",
                        "message": str(error),
                    },
                )
                return
            except Exception as error:
                self.send_json(
                    500,
                    {
                        "error": "atcoder_submit_failure",
                        "message": f"{type(error).__name__}: {error}",
                    },
                )
                return
            self.send_json(200, result)
            return

        code = payload.get("code")
        stdin = payload.get("stdin", "")
        if not isinstance(code, str) or not isinstance(stdin, str):
            self.send_json(400, {"error": "code_and_stdin_must_be_strings"})
            return

        timeout_ms = clamp_timeout(payload.get("timeoutMs"))

        try:
            result = (
                debug_python(code, stdin, timeout_ms)
                if self.path == "/debug"
                else run_python(code, stdin, timeout_ms)
            )
        except Exception as error:
            self.send_json(
                500,
                {
                    "error": "runner_failure",
                    "message": f"{type(error).__name__}: {error}",
                },
            )
            return

        self.send_json(200, result)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), RunnerHandler)
    print(f"AtCrafter Runner listening on http://{HOST}:{PORT}")
    print(f"Problems directory: {PROBLEMS_DIR}")
    print("AtCoder source: problem browsing plus oj-api backed submission are available.")
    print("WARNING: code execution is not sandboxed; run only code you trust.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("AtCrafter Runner stopped")


if __name__ == "__main__":
    main()
