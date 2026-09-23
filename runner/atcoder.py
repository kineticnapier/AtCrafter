from __future__ import annotations

import html
import json
import re
import threading
import time
from html.parser import HTMLParser
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ATCODER_PROBLEMS_BASE = "https://kenkoooo.com/atcoder/resources"
ATCODER_BASE = "https://atcoder.jp"
REMOTE_PREFIX = "atcoder:"
USER_AGENT = "AtCrafter/0.1 (+https://github.com/kineticnapier/AtCrafter)"
RESOURCE_TTL_SECONDS = 30 * 60
TASK_TTL_SECONDS = 10 * 60
REQUEST_GAP_SECONDS = 1.05

_cache: dict[str, tuple[float, Any]] = {}
_last_request_by_host: dict[str, float] = {}
_request_lock = threading.Lock()


class AtCoderError(RuntimeError):
    pass


def _host_for(url: str) -> str:
    return url.split("/", 3)[2].lower()


def _fetch(url: str, ttl_seconds: float) -> bytes:
    now = time.monotonic()
    cached = _cache.get(url)
    if cached is not None and now - cached[0] < ttl_seconds:
        return cached[1]

    host = _host_for(url)
    with _request_lock:
        now = time.monotonic()
        cached = _cache.get(url)
        if cached is not None and now - cached[0] < ttl_seconds:
            return cached[1]

        last = _last_request_by_host.get(host, 0.0)
        wait = REQUEST_GAP_SECONDS - (now - last)
        if wait > 0:
            time.sleep(wait)

        request = Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "ja,en;q=0.8",
            },
        )
        try:
            with urlopen(request, timeout=8.0) as response:
                body = response.read()
        except HTTPError as error:
            raise AtCoderError(f"AtCoder HTTP {error.code}: {url}") from error
        except (URLError, TimeoutError, OSError) as error:
            raise AtCoderError(f"AtCoder connection failed: {error}") from error
        finally:
            _last_request_by_host[host] = time.monotonic()

        _cache[url] = (time.monotonic(), body)
        return body


def _fetch_json(url: str) -> Any:
    body = _fetch(url, RESOURCE_TTL_SECONDS)
    try:
        return json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AtCoderError("AtCoder Problems returned invalid JSON") from error


def _fetch_text(url: str) -> str:
    body = _fetch(url, TASK_TTL_SECONDS)
    try:
        return body.decode("utf-8")
    except UnicodeDecodeError as error:
        raise AtCoderError("AtCoder returned invalid UTF-8") from error


def _latest_abc_from_pairs(pairs: list[dict[str, Any]]) -> str | None:
    latest_number = -1
    latest_id: str | None = None
    for pair in pairs:
        contest_id = pair.get("contest_id")
        if not isinstance(contest_id, str):
            continue
        match = re.fullmatch(r"abc(\d+)", contest_id)
        if match is None:
            continue
        number = int(match.group(1))
        if number > latest_number:
            latest_number = number
            latest_id = contest_id
    return latest_id


def list_default_problems() -> list[dict[str, str]]:
    """Return tasks from the newest ABC known to AtCoder Problems.

    This deliberately needs only one AtCoder Problems resource request, so opening the
    existing Terminal screen remains quick. Contest browsing can be added on top later.
    """
    data = _fetch_json(f"{ATCODER_PROBLEMS_BASE}/contest-problem.json")
    if not isinstance(data, list):
        raise AtCoderError("contest-problem.json has an unexpected shape")

    pairs = [item for item in data if isinstance(item, dict)]
    contest_id = _latest_abc_from_pairs(pairs)
    if contest_id is None:
        return []

    tasks: list[tuple[str, str]] = []
    for pair in pairs:
        if pair.get("contest_id") != contest_id:
            continue
        problem_id = pair.get("problem_id")
        problem_index = pair.get("problem_index")
        if not isinstance(problem_id, str) or not isinstance(problem_index, str):
            continue
        tasks.append((problem_index, problem_id))

    tasks.sort(key=lambda item: item[0])
    contest_label = contest_id.upper()
    return [
        {
            "id": f"{REMOTE_PREFIX}{contest_id}:{problem_id}",
            "title": f"[AtCoder {contest_label}] {problem_index}",
        }
        for problem_index, problem_id in tasks
    ]


def is_remote_problem_id(problem_id: str) -> bool:
    return problem_id.startswith(REMOTE_PREFIX)


def _parse_remote_id(problem_id: str) -> tuple[str, str]:
    if not is_remote_problem_id(problem_id):
        raise AtCoderError("not an AtCoder problem id")
    parts = problem_id.split(":", 2)
    if len(parts) != 3:
        raise AtCoderError("invalid AtCoder problem id")
    contest_id, task_id = parts[1], parts[2]
    if re.fullmatch(r"[a-z0-9_-]+", contest_id) is None:
        raise AtCoderError("invalid contest id")
    if re.fullmatch(r"[a-z0-9_-]+", task_id) is None:
        raise AtCoderError("invalid task id")
    return contest_id, task_id


class _TaskParser(HTMLParser):
    BLOCK_TAGS = {"p", "div", "section", "li", "ul", "ol"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.lang_depth: int | None = None
        self.in_heading = False
        self.heading_buffer: list[str] = []
        self.current_heading = ""
        self.in_pre = False
        self.pre_buffer: list[str] = []
        self.statement_parts: list[str] = []
        self.samples_in: dict[int, str] = {}
        self.samples_out: dict[int, str] = {}
        self.title_buffer: list[str] = []
        self.in_title = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.depth += 1
        attr_map = dict(attrs)
        classes = set((attr_map.get("class") or "").split())
        if self.lang_depth is None and tag == "span" and "lang-ja" in classes:
            self.lang_depth = self.depth

        if tag == "title":
            self.in_title = True

        if not self._in_japanese():
            return
        if tag == "h3":
            self.in_heading = True
            self.heading_buffer = []
        elif tag == "pre":
            self.in_pre = True
            self.pre_buffer = []
        elif tag == "br":
            self._append_statement("\n")

    def handle_endtag(self, tag: str) -> None:
        if self._in_japanese():
            if tag == "h3" and self.in_heading:
                self.in_heading = False
                self.current_heading = _clean_inline("".join(self.heading_buffer))
                if not _sample_heading(self.current_heading):
                    self.statement_parts.append(f"\n\n## {self.current_heading}\n")
            elif tag == "pre" and self.in_pre:
                self.in_pre = False
                text = html.unescape("".join(self.pre_buffer)).replace("\r\n", "\n").replace("\r", "\n").strip("\n")
                sample = _sample_heading(self.current_heading)
                if sample is not None:
                    kind, number = sample
                    if kind == "in":
                        self.samples_in[number] = text + "\n"
                    else:
                        self.samples_out[number] = text + "\n"
                else:
                    self.statement_parts.append("\n" + text + "\n")
            elif tag in self.BLOCK_TAGS and not self.in_pre and not self.in_heading:
                # Sample input/output blocks are rendered separately by Minecraft.
                if _sample_heading(self.current_heading) is None:
                    self.statement_parts.append("\n")

        if tag == "title":
            self.in_title = False
        if self.lang_depth == self.depth:
            self.lang_depth = None
        self.depth -= 1

    def handle_data(self, data: str) -> None:
        if self.in_title:
            self.title_buffer.append(data)
        if not self._in_japanese():
            return
        if self.in_heading:
            self.heading_buffer.append(data)
            return
        if self.in_pre:
            self.pre_buffer.append(data)
            return
        if _sample_heading(self.current_heading) is not None:
            return
        self._append_statement(data)

    def _append_statement(self, text: str) -> None:
        if text:
            self.statement_parts.append(text)

    def _in_japanese(self) -> bool:
        return self.lang_depth is not None and self.depth >= self.lang_depth

    def title(self) -> str:
        title = _clean_inline("".join(self.title_buffer))
        # Typical page title: "A - Foo" or "A - Foo - AtCoder".
        title = re.sub(r"\s*-\s*AtCoder\s*$", "", title)
        return title

    def statement(self) -> str:
        text = html.unescape("".join(self.statement_parts))
        text = text.replace("\xa0", " ")
        lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines()]
        compact: list[str] = []
        blank = False
        for line in lines:
            if not line:
                if compact and not blank:
                    compact.append("")
                blank = True
            else:
                compact.append(line)
                blank = False
        return "\n".join(compact).strip()

    def samples(self) -> list[dict[str, str]]:
        result: list[dict[str, str]] = []
        for number in sorted(set(self.samples_in) & set(self.samples_out)):
            result.append(
                {
                    "name": f"Sample {number}",
                    "stdin": self.samples_in[number],
                    "expected": self.samples_out[number],
                }
            )
        return result


def _clean_inline(text: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def _sample_heading(heading: str) -> tuple[str, int] | None:
    match = re.fullmatch(r"入力例\s*(\d+)", heading)
    if match is not None:
        return "in", int(match.group(1))
    match = re.fullmatch(r"出力例\s*(\d+)", heading)
    if match is not None:
        return "out", int(match.group(1))
    return None


def load_remote_problem(problem_id: str) -> dict[str, Any]:
    contest_id, task_id = _parse_remote_id(problem_id)
    url = f"{ATCODER_BASE}/contests/{contest_id}/tasks/{task_id}?lang=ja"
    source = _fetch_text(url)
    parser = _TaskParser()
    parser.feed(source)

    title = parser.title()
    statement = parser.statement()
    samples = parser.samples()
    if not title:
        title = task_id
    if not statement:
        raise AtCoderError("AtCoder problem statement could not be parsed")

    return {
        "id": problem_id,
        "title": f"[AtCoder {contest_id.upper()}] {title}",
        "statement": statement,
        "defaultCode": "import sys\n\n\ndef solve():\n    pass\n\n\nif __name__ == \"__main__\":\n    solve()\n",
        "samples": samples,
        # Hidden AtCoder judge data is not available locally. The existing Sample button works,
        # while the Submit button stays disabled until authenticated submission is implemented.
        "tests": [],
    }
