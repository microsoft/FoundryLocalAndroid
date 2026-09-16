package com.microsoft.foundrylocal.lab

data class FoundryModelIdentity(
    val alias: String = "",
    val name: String = "",
    val displayName: String = "",
    val version: String = "",
    val uri: String = ""
)

object FoundryModelIdentityResolver {
    fun catalogId(identity: FoundryModelIdentity): String =
        identity.name.trim().ifBlank { identity.alias.trim() }

    fun isSameModel(first: FoundryModelIdentity, second: FoundryModelIdentity): Boolean {
        val firstVersion = versionKey(first)
        val secondVersion = versionKey(second)
        if (firstVersion.isNotBlank() && secondVersion.isNotBlank() && firstVersion != secondVersion) {
            return false
        }
        val firstKeys = identityKeys(first)
        val secondKeys = identityKeys(second)
        return firstKeys.isNotEmpty() && firstKeys.any(secondKeys::contains)
    }

    fun isCached(
        selected: FoundryModelIdentity,
        cachedModels: List<FoundryModelIdentity>,
        sdkReportsCached: Boolean,
        downloadConfirmed: Boolean
    ): Boolean = sdkReportsCached || downloadConfirmed || cachedModels.any { isSameModel(selected, it) }

    fun preferredAlias(
        candidates: List<FoundryModelIdentity>,
        existingAlias: String,
        cachedModels: List<FoundryModelIdentity>,
        fallbackAlias: String
    ): String = candidates.firstOrNull { it.alias.equals(existingAlias, ignoreCase = true) }?.alias
        ?: candidates.firstOrNull { candidate -> cachedModels.any { isSameModel(candidate, it) } }?.alias
        ?: candidates.firstOrNull {
            isSameModel(it, FoundryModelIdentity(alias = fallbackAlias))
        }?.alias
        ?: candidates.firstOrNull()?.alias.orEmpty()

    fun isUsableDownload(successful: Boolean, progressPercent: Int): Boolean =
        successful || progressPercent >= 99

    fun shouldRevalidateActiveModel(
        isCached: Boolean,
        sdkReportsLoaded: Boolean,
        activeReceiptMatches: Boolean,
        sessionAlreadyValidated: Boolean
    ): Boolean = isCached && activeReceiptMatches && !sdkReportsLoaded && !sessionAlreadyValidated

    private fun identityKeys(identity: FoundryModelIdentity): Set<String> = buildSet {
        addIdentityValue(identity.alias)
        addIdentityValue(identity.name)
        addIdentityValue(identity.displayName)
        addIdentityValue(identity.uri)
        if (identity.version.isNotBlank()) {
            addIdentityValue("${identity.alias}:${identity.version}")
            addIdentityValue("${identity.displayName}:${identity.version}")
        }
    }

    private fun MutableSet<String>.addIdentityValue(value: String) {
        val normalized = value.trim().trimEnd('/').lowercase()
        if (normalized.isBlank()) return
        add(normalized)
        add(normalized.replace(Regex(":\\d+$"), ""))
    }

    private fun versionKey(identity: FoundryModelIdentity): String {
        identity.version.trim().lowercase().takeIf(String::isNotBlank)?.let { return it }
        listOf(identity.name, identity.alias, identity.displayName).forEach { value ->
            Regex(":([^:]+)$").find(value.trim())?.groupValues?.get(1)?.lowercase()?.let { return it }
        }
        return Regex("/versions/([^/]+)", RegexOption.IGNORE_CASE)
            .find(identity.uri.trim())
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            .orEmpty()
    }
}
