# AtCrafter Runner

Local Python execution service used by the Minecraft mod.

## Start

```powershell
python runner/main.py
```

The service listens only on `127.0.0.1:8765`.

## Endpoints

- `GET /health` — runner status
- `POST /run` — execute Python code

Example request body for `/run`:

```json
{
  "code": "print(input())",
  "stdin": "hello\n",
  "timeoutMs": 2000
}
```

## Security

The runner is intentionally minimal and **does not sandbox executed code yet**. It should only be used with code you trust. A later version can move execution behind a stronger isolation layer.
