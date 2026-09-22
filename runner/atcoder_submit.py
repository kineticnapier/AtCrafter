from __future__ import annotations

import json
import re
import shutil
import subprocess
import sysconfig
import tempfile
from pathlib import Path
from typing import Any

ATCODER_BASE = "https://atcoder.jp"
REMOTE_ID_RE = re.compile(r"atcoder:(abc\d+):([a-z0-9_-]+)\Z")


class AtCoderSubmitError(RuntimeError):
    pass


def problem_url(problem_id: str) -> str:
    match = REMOTE_ID_RE.fullmatch(problem_id)
    if match is None:
        raise AtCoderSubmitError("invalid AtCoder problem id")
    contest_id, task_id = match.groups()
    return f"{ATCODER_BASE}/contests/{contest_id}/tasks/{task_id}"


def _oj_api_path() -> str | None:
    executable = shutil.which("oj-api")
    if executable is not None:
        return executable

    scripts = sysconfig.get_path("scripts")
    if scripts:
        scripts_dir = Path(scripts)
        for name in ("oj-api.exe", "oj-api"):
            candidate = scripts_dir / name
            if candidate.is_file():
                return str(candidate)
    return None


def _run_oj_api(arguments: list[str], timeout_seconds: float = 30.0) -> dict[str, Any]:
    executable = _oj_api_path()
    if executable is None:
        raise AtCoderSubmitError(
            "oj-api が見つかりません。python -m pip install online-judge-api-client を実行してください。"
        )

    try:
        process = subprocess.run(
            [executable, *arguments],
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise AtCoderSubmitError("oj-api がタイムアウトしました。") from error
    except OSError as error:
        raise AtCoderSubmitError(f"oj-api を起動できませんでした: {error}") from error

    stdout = process.stdout.strip()
    if not stdout:
        detail = process.stderr.strip()
        raise AtCoderSubmitError(detail or f"oj-api exited with code {process.returncode}")

    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as error:
        detail = process.stderr.strip()
        raise AtCoderSubmitError(detail or "oj-api が不正なJSONを返しました。") from error

    if not isinstance(payload, dict):
        raise AtCoderSubmitError("oj-api が不正な応答を返しました。")

    status = payload.get("status")
    if status != "ok":
        messages = payload.get("messages")
        if isinstance(messages, list):
            message = " / ".join(str(item) for item in messages if item)
        else:
            message = ""
        if not message:
            message = process.stderr.strip() or f"oj-api failed with code {process.returncode}"
        raise AtCoderSubmitError(message)

    result = payload.get("result")
    if not isinstance(result, dict):
        raise AtCoderSubmitError("oj-api のresultがありません。")
    return result


def session_status() -> dict[str, Any]:
    if _oj_api_path() is None:
        return {
            "available": False,
            "loggedIn": False,
            "message": "oj-api が見つかりません。online-judge-api-client をインストールしてください。",
        }

    try:
        result = _run_oj_api(["login-service", f"{ATCODER_BASE}/", "--check"], timeout_seconds=20.0)
    except AtCoderSubmitError as error:
        return {
            "available": True,
            "loggedIn": False,
            "message": str(error),
        }

    logged_in = bool(result.get("loggedIn", False))
    return {
        "available": True,
        "loggedIn": logged_in,
        "message": "AtCoder にログイン済みです。" if logged_in else "AtCoder にログインしていません。",
    }


def submit_code(problem_id: str, code: str) -> dict[str, str]:
    if not isinstance(code, str) or not code.strip():
        raise AtCoderSubmitError("提出コードが空です。")

    session = session_status()
    if not session["available"]:
        raise AtCoderSubmitError(str(session["message"]))
    if not session["loggedIn"]:
        raise AtCoderSubmitError(
            "AtCoder にログインしていません。PC側で oj login https://atcoder.jp/ を実行してください。"
        )

    url = problem_url(problem_id)
    with tempfile.TemporaryDirectory(prefix="atcrafter-submit-") as temp_dir:
        source_path = Path(temp_dir) / "main.py"
        source_path.write_text(code, encoding="utf-8", newline="\n")

        language = _run_oj_api(
            ["guess-language-id", url, "--file", str(source_path)],
            timeout_seconds=30.0,
        )
        language_id = str(language.get("id", "")).strip()
        language_description = str(language.get("description", "")).strip()
        if not language_id:
            raise AtCoderSubmitError("Python の提出言語IDを取得できませんでした。")

        submission = _run_oj_api(
            [
                "submit-code",
                url,
                "--file",
                str(source_path),
                "--language",
                language_id,
            ],
            timeout_seconds=45.0,
        )

    submission_url = str(submission.get("url", "")).strip()
    if not re.fullmatch(r"https://atcoder\.jp/contests/[a-z0-9_-]+/submissions/\d+", submission_url):
        raise AtCoderSubmitError("提出は完了しましたが、提出URLを取得できませんでした。")

    return {
        "url": submission_url,
        "problemUrl": url,
        "languageId": language_id,
        "languageDescription": language_description or language_id,
    }
