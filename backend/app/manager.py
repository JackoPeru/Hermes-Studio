"""GPU-manager coordination — never bypass with kill/reset. Read-only status + queue submit."""
from __future__ import annotations
import httpx

class ManagerClient:
    def __init__(self, base_url: str, api_key: str = "", client: httpx.AsyncClient | None = None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._client = client

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}

    async def status(self) -> dict:
        if self._client is not None:
            r = await self._client.get(f"{self.base_url}/status", headers=self._headers())
            r.raise_for_status()
            return r.json()
        async with httpx.AsyncClient(timeout=15) as cx:
            r = await cx.get(f"{self.base_url}/status", headers=self._headers())
            r.raise_for_status()
            return r.json()
