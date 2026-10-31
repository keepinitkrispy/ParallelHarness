package com.androidharness.app.llm

object ParallelDefaults {
    const val LOCAL_PROVIDER_ID = "parallel-local-qwen"
    // Legacy provider ID retained so prior preferences survive the upgrade.
    // All inference is routed through the Mac bridge to the trained Kaggle GPU.
    const val LOCAL_MODEL = "jwest33/qwen3-vl-8b-instruct-null-space-abliterated"
    const val GPU_BRIDGE = "http://127.0.0.1:18080/v1"
    val localProvider = ProviderConfig(
        id = LOCAL_PROVIDER_ID,
        name = "Qwen 8B + LoRA (Kaggle GPU)",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = GPU_BRIDGE,
        model = LOCAL_MODEL,
    )
}
