"""Stili prompt per la generazione immagini.

Gli stili sono PROMPT TEMPLATE ONLY (nessun cambio modello): il preset viene
appeso al prompt utente in modo tracciabile. I workflow ComfyUI veri vivono
nel GPU-manager (preset versionati): questo backend non costruisce più
workflow diretti.
"""
from __future__ import annotations

DEFAULT_NEGATIVE = "low quality, blurry, watermark, text, deformed"

# Style presets are PROMPT TEMPLATES ONLY (no model change). Tracked + transparent.
STYLE_TEMPLATES = {
    "cinematico": "{p}, cinematic lighting, film still, shallow depth of field",
    "anime": "{p}, anime style, cel shaded, vibrant",
    "realistico": "{p}, photorealistic, natural light, high detail",
    "fantasy": "{p}, epic fantasy art, painterly, dramatic atmosphere",
}

def apply_style(prompt: str, preset: str | None) -> str:
    if not preset:
        return prompt
    t = STYLE_TEMPLATES.get(preset.lower())
    return t.format(p=prompt) if t else prompt
