package com.androidharness.app.llm

object ParallelDefaults {
    const val LOCAL_PROVIDER_ID = "parallel-local-qwen"
    const val LOCAL_MODEL = "ggml-org/Qwen3-1.7B-GGUF:Q4_K_M"
    val localProvider = ProviderConfig(
        id = LOCAL_PROVIDER_ID,
        name = "Local Qwen 1.7B",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:8080/v1",
        model = LOCAL_MODEL,
    )
}
