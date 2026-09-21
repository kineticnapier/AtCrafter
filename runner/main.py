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

HOST = "127.0.0.1"
PORT = 8765
MAX_REQUEST_BYTES = 2 * 1024 * 1024
MAX_OUTPUT_CHARS = 1_000_000
DEFAULT_TIMEOUT_MS = 2_000
MAX_TIMEOUT_MS = 10_000
ROOT_DIR = Path(__file__).resolve().parent.parent
PROBLEMS_DIR = ROOT_DIR / "problems"

DEBUG_DRIVER = r'''
import json
import sys
import traceback

TARGET = sys.argv[1]
TRACE_FILE = sys.argv[2]
MAX_STEPS = 5000
MAX_VALUE_CHARS = 240
steps = []
trace_truncated = False


def safe_repr(value):
    try:
        text = repr(value)
    except Exception as error:
        text = f"<repr failed: {type(error).__name__}>"
    if len(text) > MAX_VALUE_CHARS:
        return text[: MAX_VALUE_CHARS - 3] + "..."
    return text


def snapshot(frame):
    result = {}
    for name, value in frame.f_locals.items():
        if name.startswith("__"):
            continue
        result[str(name)] = safe_repr(value)
    return result


def tracer(frame, event, arg):
    global trace_truncated
    if frame.f_code.co_filename == TARGET and event in ("line", "return"):
        if len(steps) < MAX_STEPS:
            steps.append({
                "line": frame.f_lineno,
                "event": event,
                "locals": snapshot(frame),
            })
        else:
            trace_truncated = True
    return tracer


namespace = {"__name__": "__main__", "__file__": TARGET}
try:
    source = open(TARGET, "r", encoding="utf-8").read()
    compiled = compile(source, TARGET, "exec")
    sys.settrace(tracer)
    exec(compiled, namespace, namespace)
except BaseException:
    traceback.print_exc()
finally:
    sys.settrace(None)
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
    server_version = "AtCrafterRunner/0.3"

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
                    "runnerVersion": "0.3",
                    "python": sys.version.split()[0],
                    "problemCount": len(list_problems()),
                },
            )
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
        if self.path not in {"/run", "/debug"}:
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
