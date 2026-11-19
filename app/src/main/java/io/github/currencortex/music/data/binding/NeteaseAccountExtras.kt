package io.github.currencortex.music.data.binding

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** NetEase account medals (勋章), shown on the NetEase binding page. */
@Serializable data class NeteaseMedals(val total: Int = 0, val medals: List<NeteaseMedal> = emptyList())
@Serializable data class NeteaseMedal(val name: String = "", val image: String = "")

@Serializable enum class NeteaseMembershipTier { NONE, VIP, SVIP }
@Serializable data class NeteaseMembership(val tier: NeteaseMembershipTier = NeteaseMembershipTier.NONE,
    val label: String = "")

/**
 * Tolerant parsers for the official NetEase medal/membership payloads. Field names
 * vary across app versions, so every lookup degrades to a default instead of throwing.
 */
internal fun parseNeteaseMedals(data: JsonObject, uid: Long): NeteaseMedals {
    fun JsonObject.obj(key: String) = (this[key] as? JsonObject)
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val list = data.obj("medals") ?: data.obj("list") ?: data.obj("data")
    val entries = list?.let { inner ->
        (inner["medals"] as? kotlinx.serialization.json.JsonArray)
            ?: (inner["list"] as? kotlinx.serialization.json.JsonArray)
            ?: (inner["data"] as? kotlinx.serialization.json.JsonArray)
    }
    val medals = entries.orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val name = entry.text("name").ifBlank { entry.text("title") }
        if (name.isBlank()) null
        else NeteaseMedal(name, entry.text("image").ifBlank { entry.text("icon").ifBlank { entry.text("pic") } })
    }
    val total = data.text("total").toIntOrNull()
        ?: list?.text("total")?.toIntOrNull()
        ?: medals.size
    return NeteaseMedals(total, medals)
}

internal fun parseNeteaseMembership(data: JsonObject, uid: Long = 0): NeteaseMembership {
    fun JsonObject.obj(key: String) = (this[key] as? JsonObject)
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull in setOf("true", "1")
    fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    val member = data.obj("associator") ?: data.obj("vip") ?: data.obj("member")
    if (member == null) return NeteaseMembership()
    val level = member.int("vipLevel") ?: member.int("redVipLevel") ?: member.int("level") ?: 0
    val svip = member.bool("svip") || member.int("svipLevel")?.let { it > 0 } == true ||
        member.text("tier").contains("svip", true)
    val tier = when {
        svip || level >= 7 -> NeteaseMembershipTier.SVIP
        level > 0 || member.bool("vip") || member.text("vipCode").isNotBlank() -> NeteaseMembershipTier.VIP
        else -> NeteaseMembershipTier.NONE
    }
    val label = member.text("describe").ifBlank { member.text("title").ifBlank { member.text("label") } }
        .ifBlank { when (tier) {
            NeteaseMembershipTier.SVIP -> "SVIP"
            NeteaseMembershipTier.VIP -> "VIP"
            NeteaseMembershipTier.NONE -> ""
        } }
    return NeteaseMembership(tier, label)
}
