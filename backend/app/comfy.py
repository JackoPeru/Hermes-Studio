"""ComfyUIAdapter — solo letture diagnostiche (coda/stato nodi).

Tutti i submit passano dal GPU-manager (orchestrazione VRAM/modi):
niente submit diretti, niente bypass. Injectable HTTP per test.
"""
from __future__ import annotations
import httpx


class ComfyUIAdapter:
    def __init__(self, base_url: str, client: httpx.AsyncClient | None = None):
        self.base_url = base_url.rstrip("/")
        self._client = client

    def _cx(self) -> httpx.AsyncClient:
        if self._client is not None:
            return self._client
        return httpx.AsyncClient(timeout=30)

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
