"""ComfyUIAdapter — talks ONLY to verified local ComfyUI API. Injectable HTTP for tests."""
from __future__ import annotations
import httpx


class ComfyUIAdapter:
    def __init__(self, base_url: str, client: httpx.AsyncClient | None = None):
        self.base_url = base_url.rstrip("/")
        self._client = client

    def _cx(self) -> httpx.AsyncClient:
        return self._client or httpx.AsyncClient(timeout=30)

    async def _close_if_owned(self, cx: httpx.AsyncClient) -> None:
        if self._client is None:
            await cx.aclose()

    async def object_info(self) -> dict:
        cx = self._cx()
        try:
            r = await cx.get(f"{self.base_url}/object_info")
            r.raise_for_status()
            return r.json()
        finally:
            await self._close_if_owned(cx)

    async def queue(self) -> dict:
        cx = self._cx()
        try:
            r = await cx.get(f"{self.base_url}/queue")
            r.raise_for_status()
            return r.json()
        finally:
            await self._close_if_owned(cx)

    async def submit(self, prompt: dict) -> str:
        cx = self._cx()
        try:
            r = await cx.post(f"{self.base_url}/prompt", json={"prompt": prompt})
            r.raise_for_status()
            return str(r.json()["prompt_id"])
        finally:
            await self._close_if_owned(cx)

    async def history(self, prompt_id: str) -> dict:
        cx = self._cx()
        try:
            r = await cx.get(f"{self.base_url}/history/{prompt_id}")
            r.raise_for_status()
            return r.json()
        finally:
            await self._close_if_owned(cx)

    async def fetch_view(self, filename: str, subfolder: str = "", ftype: str = "output") -> bytes:
        cx = self._cx()
        try:
            r = await cx.get(
                f"{self.base_url}/view",
                params={"filename": filename, "subfolder": subfolder, "type": ftype},
            )
            r.raise_for_status()
            return r.content
        finally:
            await self._close_if_owned(cx)
