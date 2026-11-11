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

    async def status(self, timeout: float = 15.0) -> dict:
        if self._client is not None:
            r = await self._client.get(f"{self.base_url}/status", headers=self._headers())
            r.raise_for_status()
            return r.json()
        async with httpx.AsyncClient(timeout=timeout) as cx:
            r = await cx.get(f"{self.base_url}/status", headers=self._headers())
            r.raise_for_status()
            return r.json()

    async def submit_video(self, preset: str, params: dict) -> dict:
        """Accoda un job video (image-to-video turbo). 202 -> {job_id,...}."""
        if self._client is not None:
            r = await self._client.post(
                f"{self.base_url}/jobs/video",
                json={"preset": preset, "parameters": params}, headers=self._headers())
            r.raise_for_status()
            return r.json()
        async with httpx.AsyncClient(timeout=30) as cx:
            r = await cx.post(
                f"{self.base_url}/jobs/video",
                json={"preset": preset, "parameters": params}, headers=self._headers())
            r.raise_for_status()
            return r.json()

    async def get_job(self, manager_id: str) -> dict:
        if self._client is not None:
            r = await self._client.get(f"{self.base_url}/jobs/{manager_id}", headers=self._headers())
            r.raise_for_status()
            return r.json()
        async with httpx.AsyncClient(timeout=15) as cx:
            r = await cx.get(f"{self.base_url}/jobs/{manager_id}", headers=self._headers())
            r.raise_for_status()
            return r.json()

    async def cancel_job(self, manager_id: str) -> dict:
        if self._client is not None:
            r = await self._client.post(
                f"{self.base_url}/jobs/{manager_id}/cancel", headers=self._headers())
            r.raise_for_status()
            return r.json()
        async with httpx.AsyncClient(timeout=15) as cx:
            r = await cx.post(
                f"{self.base_url}/jobs/{manager_id}/cancel", headers=self._headers())
            r.raise_for_status()
            return r.json()
