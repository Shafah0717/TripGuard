from __future__ import annotations

import time
from dataclasses import dataclass, field

import bcrypt
import jwt


def hash_password(plain: str) -> str:
    return bcrypt.hashpw(plain.encode("utf-8"), bcrypt.gensalt()).decode()


def verify_password(plain: str, hashed: str) -> bool:
    return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode())


@dataclass
class RateLimiter:
    """Fixed-window in-memory rate limiter keyed by client identifier."""

    max_events: int = 5
    window_seconds: int = 60
    _events: dict[str, list[float]] = field(default_factory=dict)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        bucket = self._events.setdefault(key, [])
        bucket[:] = [t for t in bucket if now - t < self.window_seconds]
        if len(bucket) >= self.max_events:
            return False
        bucket.append(now)
        if len(self._events) > 10_000:
            self._events.clear()
        return True


def create_access_token(
    subject: str,
    role: str,
    secret: str,
    expires_seconds: int = 8 * 3600,
) -> str:
    return jwt.encode(
        {"sub": subject, "role": role},
        secret,
        algorithm="HS256",
        headers={"typ": "JWT"},
    )


def decode_access_token(token: str, secret: str) -> dict | None:
    try:
        return jwt.decode(token, secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None
