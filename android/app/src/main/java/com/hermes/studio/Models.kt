package com.hermes.studio

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Health(val status: String = "", val backend_version: String = "", val api_version: String = "")

@JsonClass(generateAdapter = true)
data class CapTask(
    val task: String = "",
    val engine: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    val disabled_reason: String? = null,
    val supports_reference: Boolean = false,
    val notes: String = ""
)

@JsonClass(generateAdapter = true)
data class Capabilities(val image: List<CapTask> = emptyList(), val video: List<CapTask> = emptyList())

@JsonClass(generateAdapter = true)
data class Project(
    val id: String = "",
    val name: String = "",
    val created_at: String = "",
    val asset_ids: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProjectCreate(val name: String)

@JsonClass(generateAdapter = true)
data class JobCreate(
    val taskType: String,
    val prompt: String,
    val engine: String? = null,
    val model: String? = null,
    val negative_prompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val seed: Long? = null,
    val style_preset: String? = null,
    val asset_ids: List<String> = emptyList(),
    val idempotency_key: String? = null
)

@JsonClass(generateAdapter = true)
data class Job(
    val id: String = "",
    val taskType: String = "",
    val status: String = "",
    val engine: String = "",
    val model: String = "",
    val prompt: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val seed: Long? = null,
    val comfy_prompt_id: String? = null,
    val progress: Double? = null,
    val error: String? = null,
    val outputs: List<String> = emptyList(),
    val created_at: String = "",
    val updated_at: String = ""
)

@JsonClass(generateAdapter = true)
data class Asset(
    val id: String = "",
    val kind: String = "",
    val path: String = "",
    val thumb: String? = null,
    val job_id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val created_at: String = ""
)
