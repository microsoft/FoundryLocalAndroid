package com.microsoft.foundrylocal.lab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundryModelIdentityResolverTest {
    @Test
    fun `catalog lookup uses canonical model name instead of display alias`() {
        val identity = FoundryModelIdentity(
            alias = "qwen2.5-0.5b-instruct-generic-cpu",
            name = "qwen2.5-0.5b-instruct-generic-cpu:4"
        )

        assertTrue(FoundryModelIdentityResolver.catalogId(identity) == identity.name)
    }

    @Test
    fun `catalog lookup falls back to alias when model name is missing`() {
        val identity = FoundryModelIdentity(alias = "legacy-model:1")

        assertTrue(FoundryModelIdentityResolver.catalogId(identity) == identity.alias)
    }

    @Test
    fun `versioned aliases match their base catalog alias`() {
        assertTrue(
            FoundryModelIdentityResolver.isSameModel(
                FoundryModelIdentity(alias = "qwen2.5-coder-0.5b:4"),
                FoundryModelIdentity(alias = "qwen2.5-coder-0.5b")
            )
        )
    }

    @Test
    fun `different model versions are not treated as the same cached record`() {
        assertFalse(
            FoundryModelIdentityResolver.isSameModel(
                FoundryModelIdentity(name = "qwen2.5-0.5b-instruct-generic-cpu:4", version = "4"),
                FoundryModelIdentity(name = "qwen2.5-0.5b-instruct-generic-cpu:5", version = "5")
            )
        )
    }

    @Test
    fun `cached runnable model is preferred on a fresh app start`() {
        val qwen = FoundryModelIdentity(
            alias = "qwen2.5-0.5b",
            name = "qwen2.5-0.5b-instruct-generic-cpu:4",
            version = "4"
        )

        assertTrue(
            FoundryModelIdentityResolver.preferredAlias(
                candidates = listOf(FoundryModelIdentity(alias = "gemma-4-e2b-it"), qwen),
                existingAlias = "",
                cachedModels = listOf(qwen.copy()),
                fallbackAlias = "qwen2.5-coder-0.5b"
            ) == qwen.alias
        )
    }

    @Test
    fun `configured fallback is used when no model is cached`() {
        val fallback = FoundryModelIdentity(alias = "qwen2.5-coder-0.5b")

        assertTrue(
            FoundryModelIdentityResolver.preferredAlias(
                candidates = listOf(FoundryModelIdentity(alias = "gemma-4-e2b-it"), fallback),
                existingAlias = "",
                cachedModels = emptyList(),
                fallbackAlias = fallback.alias
            ) == fallback.alias
        )
    }

    @Test
    fun `completed files remain a usable download despite final callback error`() {
        assertTrue(FoundryModelIdentityResolver.isUsableDownload(false, 99))
        assertFalse(FoundryModelIdentityResolver.isUsableDownload(false, 72))
    }

    @Test
    fun `validated session does not reload on catalog refresh`() {
        assertFalse(
            FoundryModelIdentityResolver.shouldRevalidateActiveModel(
                isCached = true,
                sdkReportsLoaded = false,
                activeReceiptMatches = true,
                sessionAlreadyValidated = true
            )
        )
    }
}
