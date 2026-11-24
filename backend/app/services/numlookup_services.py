import json
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from app.core.config import settings


BASE_URL = "https://api.numlookupapi.com/v1/validate"


def lookup_phone_number(phone_number: str) -> dict:
    api_key = settings.numlookup_api_key

    if not api_key:
        raise RuntimeError("NumLookup API key is not configured.")

    encoded_number = quote(phone_number, safe="")

    request = Request(
        f"{BASE_URL}/{encoded_number}",
        headers={
            "apikey": api_key,
            "Accept": "application/json",
        },
        method="GET",
    )

    try:
        with urlopen(request, timeout=8) as response:
            result = json.loads(response.read().decode("utf-8"))

    except HTTPError as error:
        if error.code == 401:
            raise RuntimeError("NumLookup API authentication failed.")

        if error.code == 429:
            raise RuntimeError("NumLookup API quota or rate limit exceeded.")

        raise RuntimeError(
            f"NumLookup API returned HTTP {error.code}."
        )

    except URLError:
        raise RuntimeError("Unable to connect to NumLookup API.")

    except (json.JSONDecodeError, UnicodeDecodeError):
        raise RuntimeError("Invalid response received from NumLookup API.")

    fields = [
        "valid",
        "number",
        "local_format",
        "international_format",
        "country_prefix",
        "country_code",
        "country_name",
        "location",
        "carrier",
        "line_type",
        "e164_format",
        "national_format",
        "possible",
        "number_type",
        "timezones",
    ]

    return {
        key: result.get(key)
        for key in fields
    }