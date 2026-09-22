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


def _parse_problem_id(problem_id: str) -> tuple[str, str]:
    match = REMOTE_ID_RE.fullmatch(problem_id)
    if match is None:
        raise AtCoderSubmitError("invalid AtCoder problem id")
    return match.groups()


def problem_url(problem_id: str) -> str:
    contest_id, task_id = _parse_problem_id(problem_id)
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


def _useful_stderr(stderr: str) -> str:
    useful: list[str] = []
    for raw_line in stderr.splitlines():
        line = raw_line.strip()
        lower = line.lower()
        if not line:
            continue
        if "atcoder says:" in lower or "warning:" in lower or "error:" in lower:
            useful.append(line)
    if not useful:
        return ""
    return " / ".join(useful[-3:])


def _run_oj_api(arguments: list[str], timeout_seconds: float = 30.0) -> dict[str, Any]:
    executable = _oj_api_path()
    if executable is None:
        raise AtCoderSubmitError(
            "oj-api が見つかりません。python -m pip install online-judge-api-client-ng を実行してください。"
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
    stderr = process.stderr.strip()
    if not stdout:
        raise AtCoderSubmitError(_useful_stderr(stderr) or stderr or f"oj-api exited with code {process.returncode}")

    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise AtCoderSubmitError(_useful_stderr(stderr) or stderr or "oj-api が不正なJSONを返しました。") from error

    if not isinstance(payload, dict):
        raise AtCoderSubmitError("oj-api が不正な応答を返しました。")

    status = payload.get("status")
    if status != "ok":
        messages = payload.get("messages")
        if isinstance(messages, list):
            message = " / ".join(str(item) for item in messages if item)
        else:
            message = ""
        detail = _useful_stderr(stderr)
        if detail and detail not in message:
            message = f"{message} / {detail}" if message else detail
        if not message:
            message = stderr or f"oj-api failed with code {process.returncode}"
        raise AtCoderSubmitError(message)

    result = payload.get("result")
    if not isinstance(result, dict):
        raise AtCoderSubmitError("oj-api のresultがありません。")
    return result


def _version_tuple(description: str) -> tuple[int, int, int]:
    match = re.search(r"(?:cpython|python)\D*(\d+)\.(\d+)(?:\.(\d+))?", description, re.IGNORECASE)
    if match is None:
        return 0, 0, 0
    return tuple(int(part or 0) for part in match.groups())  # type: ignore[return-value]


def _language_score(language: dict[str, Any]) -> tuple[int, tuple[int, int, int], int]:
    description = str(language.get("description", ""))
    lower = description.lower()
    language_id = str(language.get("id", ""))
    try:
        numeric_id = int(language_id)
    except ValueError:
        numeric_id = 0

    if "python" not in lower:
        return -1, (0, 0, 0), numeric_id
    if "cpython" in lower:
        family_score = 300
    elif "pypy" in lower:
        family_score = 100
    elif "cython" in lower or "micropython" in lower:
        family_score = 50
    else:
        family_score = 200
    return family_score, _version_tuple(description), numeric_id


def _select_python_language(url: str, source_path: Path) -> dict[str, str]:
    problem = _run_oj_api(["get-problem", url, "--full"], timeout_seconds=30.0)
    available = problem.get("availableLanguages")
    if isinstance(available, list):
        candidates = [item for item in available if isinstance(item, dict) and _language_score(item)[0] >= 0]
        if candidates:
            selected = max(candidates, key=_language_score)
            language_id = str(selected.get("id", "")).strip()
            description = str(selected.get("description", "")).strip()
            if language_id:
                return {
                    "id": language_id,
                    "description": description or language_id,
                }

    try:
        guessed = _run_oj_api(
            ["guess-language-id", url, "--file", str(source_path)],
            timeout_seconds=30.0,
        )
    except AtCoderSubmitError as error:
        raise AtCoderSubmitError(
            "Python の提出言語を1つに決められませんでした。利用可能言語一覧の取得にも失敗しました: "
            + str(error)
        ) from error

    language_id = str(guessed.get("id", "")).strip()
    description = str(guessed.get("description", "")).strip()
    if not language_id:
        raise AtCoderSubmitError("Python の提出言語IDを取得できませんでした。")
    return {
        "id": language_id,
        "description": description or language_id,
    }


def _submission_url_from_html(contest_id: str, task_id: str, html: str) -> str | None:
    submission_pattern = re.compile(
        rf'href=["\'](/contests/{re.escape(contest_id)}/submissions/(\d+))["\']'
    )
    task_marker = f"/contests/{contest_id}/tasks/{task_id}"
    found: list[tuple[int, str]] = []
    for row in re.findall(r"<tr\b.*?</tr>", html, flags=re.IGNORECASE | re.DOTALL):
        if task_marker not in row:
            continue
        match = submission_pattern.search(row)
        if match is not None:
            found.append((int(match.group(2)), ATCODER_BASE + match.group(1)))
    if not found:
        return None
    return max(found, key=lambda item: item[0])[1]


def _latest_submission_url(problem_id: str) -> tuple[bool, str | None]:
    contest_id, task_id = _parse_problem_id(problem_id)
    try:
        import onlinejudge.utils

        session = onlinejudge.utils.get_default_session()
        with onlinejudge.utils.with_cookiejar(session):
            response = session.get(
                f"{ATCODER_BASE}/contests/{contest_id}/submissions/me",
                params={"f.Task": task_id, "orderBy": "created", "desc": "true"},
                timeout=12.0,
                allow_redirects=True,
            )
            if response.status_code != 200 or "/login" in response.url:
                return False, None
            html = response.content.decode(response.encoding or "utf-8", errors="replace")
    except Exception:
        return False, None

    return True, _submission_url_from_html(contest_id, task_id, html)


def _extract_alerts(html: str) -> list[str]:
    try:
        from bs4 import BeautifulSoup

        soup = BeautifulSoup(html, "html.parser")
        result: list[str] = []
        for alert in soup.find_all("div", attrs={"role": "alert"}):
            text = " ".join(part.strip() for part in alert.stripped_strings if part.strip())
            if text:
                result.append(text)
        return result
    except Exception:
        return []


def _submit_direct(problem_id: str, code: str, language_id: str) -> str:
    """Submit using AtCoder's HTML form directly.

    online-judge-api-client currently wraps the submit form with FormSender and reports
    every non-/submissions/me result as "it may be a rate limit".  Current AtCoder can
    reject that generated form payload with only a generic "Error." alert.  Use the same
    cookie jar, but post the four fields AtCoder's form actually requires.
    """
    contest_id, task_id = _parse_problem_id(problem_id)
    submit_url = f"{ATCODER_BASE}/contests/{contest_id}/submit"

    try:
        import onlinejudge.utils
        from bs4 import BeautifulSoup

        session = onlinejudge.utils.get_default_session()
        with onlinejudge.utils.with_cookiejar(session):
            get_response = session.get(submit_url, timeout=15.0, allow_redirects=True)
            if "/login" in get_response.url:
                raise AtCoderSubmitError("AtCoder のログインセッションが切れています。")
            if get_response.status_code != 200:
                raise AtCoderSubmitError(f"AtCoder 提出ページの取得に失敗しました: HTTP {get_response.status_code}")

            get_response.encoding = "utf-8"
            soup = BeautifulSoup(get_response.text, "html.parser")
            form = soup.find("form", action=f"/contests/{contest_id}/submit")
            if form is None:
                raise AtCoderSubmitError("AtCoder の提出フォームを見つけられませんでした。")

            csrf_input = form.find("input", attrs={"name": "csrf_token"})
            csrf_token = csrf_input.get("value") if csrf_input is not None else None
            if not isinstance(csrf_token, str) or not csrf_token:
                raise AtCoderSubmitError("AtCoder の csrf_token を取得できませんでした。")

            task_select = form.find("select", attrs={"name": "data.TaskScreenName"})
            task_option = task_select.find("option", attrs={"value": task_id}) if task_select is not None else None
            if task_option is None:
                raise AtCoderSubmitError(f"提出フォームに問題 {task_id} がありません。")

            language_select = form.find("select", attrs={"name": "data.LanguageId"})
            language_option = (
                language_select.find("option", attrs={"value": str(language_id)})
                if language_select is not None
                else None
            )
            if language_option is None:
                raise AtCoderSubmitError(f"提出フォームに言語ID {language_id} がありません。")

            post_response = session.post(
                submit_url,
                data={
                    "csrf_token": csrf_token,
                    "data.TaskScreenName": task_id,
                    "data.LanguageId": str(language_id),
                    "sourceCode": code,
                },
                headers={"Referer": submit_url},
                timeout=20.0,
                allow_redirects=True,
            )
            post_response.encoding = "utf-8"

            if post_response.status_code == 429:
                raise AtCoderSubmitError("AtCoder が HTTP 429 を返しました。少し待ってから再提出してください。")
            if post_response.status_code >= 400:
                raise AtCoderSubmitError(
                    f"AtCoder 提出POSTに失敗しました: HTTP {post_response.status_code} ({post_response.url})"
                )

            if "/login" in post_response.url:
                raise AtCoderSubmitError("提出時にAtCoderのログインセッションが切れました。")

            submission_url = _submission_url_from_html(contest_id, task_id, post_response.text)
            if submission_url is not None:
                return submission_url

            alerts = _extract_alerts(post_response.text)
            if alerts:
                raise AtCoderSubmitError(
                    "AtCoder: " + " / ".join(alerts) + f" (HTTP {post_response.status_code}, {post_response.url})"
                )

            # Successful submissions normally land on /submissions/me. If the page shape
            # changes, inspect the authenticated list once before declaring failure.
            if "/submissions/me" in post_response.url:
                list_response = session.get(
                    f"{ATCODER_BASE}/contests/{contest_id}/submissions/me",
                    params={"f.Task": task_id, "orderBy": "created", "desc": "true"},
                    timeout=12.0,
                )
                list_response.encoding = "utf-8"
                submission_url = _submission_url_from_html(contest_id, task_id, list_response.text)
                if submission_url is not None:
                    return submission_url

            raise AtCoderSubmitError(
                f"AtCoder は提出を受理しませんでした: HTTP {post_response.status_code}, {post_response.url}"
            )
    except AtCoderSubmitError:
        raise
    except Exception as error:
        raise AtCoderSubmitError(f"AtCoder 直接提出に失敗しました: {type(error).__name__}: {error}") from error


def session_status() -> dict[str, Any]:
    if _oj_api_path() is None:
        return {
            "available": False,
            "loggedIn": False,
            "message": "oj-api が見つかりません。online-judge-api-client-ng をインストールしてください。",
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
        raise AtCoderSubmitError("AtCoder にログインしていません。")

    url = problem_url(problem_id)
    before_ok, before_submission = _latest_submission_url(problem_id)

    with tempfile.TemporaryDirectory(prefix="atcrafter-submit-") as temp_dir:
        source_path = Path(temp_dir) / "main.py"
        source_path.write_text(code, encoding="utf-8", newline="\n")

        language = _select_python_language(url, source_path)
        language_id = language["id"]
        language_description = language["description"]
        submission_url = _submit_direct(problem_id, code, language_id)

    # Do not silently accept an old submission if AtCoder returned a stale page.
    if before_ok and before_submission is not None and submission_url == before_submission:
        after_ok, after_submission = _latest_submission_url(problem_id)
        if not after_ok or after_submission is None or after_submission == before_submission:
            raise AtCoderSubmitError("AtCoder の提出一覧に新しい提出が現れませんでした。")
        submission_url = after_submission

    return {
        "url": submission_url,
        "problemUrl": url,
        "languageId": language_id,
        "languageDescription": language_description,
    }
