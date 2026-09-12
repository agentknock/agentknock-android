"""Shared authenticated requests to the Google Play Developer API."""

import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen


APPLICATION = "androidpublisher/v3/applications/dev.agentknock"
HOST = "https://androidpublisher.googleapis.com"


def play_request(method, path, token, data=None, upload=False):
    headers = {"Authorization": f"Bearer {token}"}
    if data is not None:
        headers["Content-Type"] = "application/octet-stream" if upload else "application/json"
        if not upload:
            data = json.dumps(data).encode()
    request = Request(f"{HOST}/{'upload/' if upload else ''}{path}",
                      method=method, headers=headers, data=data)
    try:
        with urlopen(request, timeout=180) as response:
            body = response.read()
            return json.loads(body) if body else None
    except HTTPError as error:
        with error:
            message = error.read().decode()
        raise RuntimeError(f"Google Play {method} {path} failed ({error.code}): {message}") from None
