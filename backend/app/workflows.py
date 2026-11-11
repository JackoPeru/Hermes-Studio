"""Versioned ComfyUI workflow templates (API format) validated against real server files.

- qwen-t2i v1: from /opt/hermes/media-workflows/qwen/qwen-image-2.1-8gb-t2i.json
- qwen-edit v1: from .../qwen-image-2.1-8gb-edit.json (needs_input, experimental)
Placeholders: {{PROMPT}} {{NEGATIVE_PROMPT}} {{WIDTH}} {{HEIGHT}} {{SEED}} {{STEPS}} {{JOB_ID}} {{INPUT_IMAGE}}.
"""
from __future__ import annotations
import copy

DEFAULT_NEGATIVE = "low quality, blurry, watermark, text, deformed"

# Style presets are PROMPT TEMPLATES ONLY (no model change). Tracked + transparent.
STYLE_TEMPLATES = {
    "cinematico": "{p}, cinematic lighting, film still, shallow depth of field",
    "anime": "{p}, anime style, cel shaded, vibrant",
    "realistico": "{p}, photorealistic, natural light, high detail",
    "fantasy": "{p}, epic fantasy art, painterly, dramatic atmosphere",
}

QWEN_T2I_V1 = {
    "1": {"class_type": "UnetLoaderGGUF", "inputs": {"unet_name": "qwen-image-2.1-UC-Q4_K_M.gguf"}},
    "2": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_8b_w4a8.safetensors", "type": "qwen_image"}},
    "3": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_2.1_vae_bf16.safetensors"}},
    "4": {"class_type": "TextEncodeQwenImage21", "inputs": {
        "clip": ["2", 0], "negative_prompt": "{{NEGATIVE_PROMPT}}",
        "prompt": "{{PROMPT}}", "resolution": 1024}},
    "5": {"class_type": "EmptyLatentImage", "inputs": {
        "batch_size": 1, "height": "{{HEIGHT}}", "width": "{{WIDTH}}"}},
    "6": {"class_type": "KSampler", "inputs": {
        "cfg": 1.0, "denoise": 1.0, "latent_image": ["5", 0], "model": ["1", 0],
        "negative": ["4", 1], "positive": ["4", 0],
        "sampler_name": "euler", "scheduler": "simple",
        "seed": "{{SEED}}", "steps": "{{STEPS}}"}},
    "7": {"class_type": "VAEDecode", "inputs": {"samples": ["6", 0], "vae": ["3", 0]}},
    "8": {"class_type": "SaveImage", "inputs": {
        "filename_prefix": "journey/{{JOB_ID}}", "images": ["7", 0]}},
}

WORKFLOWS = {"qwen-t2i": {"version": 1, "template": QWEN_T2I_V1}}

def apply_style(prompt: str, preset: str | None) -> str:
    if not preset:
        return prompt
    t = STYLE_TEMPLATES.get(preset.lower())
    return t.format(p=prompt) if t else prompt

def build_qwen_t2i(*, prompt: str, negative: str, width: int, height: int,
                   seed: int, steps: int, job_id: str) -> dict:
    tpl = copy.deepcopy(QWEN_T2I_V1)
    # Sostituzione esatta per nodo: niente str.replace sul JSON (il prompt utente
    # potrebbe contenere {{WIDTH}} e verrebbe corrotto). Solo i campi template.
    tpl["4"]["inputs"]["prompt"] = prompt
    tpl["4"]["inputs"]["negative_prompt"] = negative or DEFAULT_NEGATIVE
    tpl["5"]["inputs"]["width"] = int(width)
    tpl["5"]["inputs"]["height"] = int(height)
    tpl["6"]["inputs"]["seed"] = int(seed)
    tpl["6"]["inputs"]["steps"] = int(steps)
    tpl["8"]["inputs"]["filename_prefix"] = f"journey/{job_id}"
    return tpl
