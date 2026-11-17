"""Stili prompt per la generazione immagini.

Gli stili sono PROMPT TEMPLATE ONLY (nessun cambio modello): il preset viene
appeso al prompt utente in modo tracciabile. I workflow ComfyUI veri vivono
nel GPU-manager (preset versionati): questo backend non costruisce più
workflow diretti.
"""
from __future__ import annotations

DEFAULT_NEGATIVE = "low quality, blurry, watermark, text, deformed"

# Style presets are PROMPT TEMPLATES ONLY (no model change). Optional: none selected = prompt puro.
STYLE_TEMPLATES = {
    "cinematico": "{p}, cinematic lighting, film still, shallow depth of field",
    "fotografico": "{p}, professional photography, natural light, 50mm lens, high detail",
    "ritratto": "{p}, studio portrait, soft key light, detailed skin, bokeh background",
    "realistico": "{p}, photorealistic, natural light, high detail",
    "anime": "{p}, anime style, cel shaded, vibrant",
    "illustrazione": "{p}, digital illustration, clean lines, rich colors",
    "acquerello": "{p}, watercolor painting, soft washes, paper texture",
    "fumetto": "{p}, comic book art, bold ink lines, halftone shading",
    "fantasy": "{p}, epic fantasy art, painterly, dramatic atmosphere",
    "cyberpunk": "{p}, cyberpunk, neon lights, rain, futuristic city",
    "3d": "{p}, 3D render, octane, soft global illumination, depth of field",
    "noir": "{p}, film noir, high contrast black and white, dramatic shadows",
    "minimale": "{p}, minimalist composition, negative space, clean background",
    "vintage": "{p}, vintage film look, warm grain, faded colors",
}

def apply_style(prompt: str, preset: str | None) -> str:
    if not preset:
        return prompt
    t = STYLE_TEMPLATES.get(preset.lower())
    return t.format(p=prompt) if t else prompt
