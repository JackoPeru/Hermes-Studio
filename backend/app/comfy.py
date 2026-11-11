"""ComfyUIAdapter — talks ONLY to verified local ComfyUI API. Injectable HTTP for tests."""
from __future__ import annotations
import httpx

MAX_VIEW_BYTES = 80 * 1024 * 1024


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
            # Streaming + cap: PNG Comfy possono superare i 200MB, niente OOM in save_bytes.
            req = cx.build_request(
                "GET",
                f"{self.base_url}/view",
                params={"filename": filename, "subfolder": subfolder, "type": ftype},
            )
            r = await cx.send(req, stream=True)
            r.raise_for_status()
            chunks: list[bytes] = []
            total = 0
            async for chunk in r.aiter_bytes(65536):
                total += len(chunk)
                if total > MAX_VIEW_BYTES:
                    raise ValueError(f"view troppo grande (>{MAX_VIEW_BYTES} byte)")
                chunks.append(chunk)
            await r.aclose()
            return b"".join(chunks)
        finally:
            await self._close_if_owned(cx)

    async def interrupt(self) -> None:
        """Best-effort: ferma la coda Comfy corrente (usato dal cancel di job running)."""
        cx = self._cx()
        try:
            r = await cx.post(f"{self.base_url}/interrupt")
            r.raise_for_status()
        finally:
            await self._close_if_owned(cx)
