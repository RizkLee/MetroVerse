package com.metrolist.music.discord

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

enum class ActivityType(val code: Int) {
    Playing(0),
    Streaming(1),
    Listening(2),
    Watching(3),
    Custom(4),
    Competing(5),
}

enum class PresenceStatus(val wire: String) {
    Online("online"),
    Idle("idle"),
    Dnd("dnd"),
    Invisible("invisible"),
}

data class ActivityPayload(
    val name: String = "",
    val type: Int,
    val applicationId: String? = null,
    val details: String? = null,
    val state: String? = null,
    val url: String? = null,
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val buttons: List<Pair<String, String>> = emptyList(),
)

object DiscordPresence {
    private const val MAX_ACTIVITY_TEXT_CODE_UNITS = 128
    private const val MAX_ACTIVITY_URL_CODE_UNITS = 512
    private const val MAX_ASSET_IMAGE_CODE_UNITS = 313
    private const val MAX_BUTTON_LABEL_CODE_UNITS = 32
    private const val MAX_ACTIVITY_BUTTONS = 2

    fun buildActivity(
        name: String = "",
        type: ActivityType,
        applicationId: String? = null,
        details: String? = null,
        state: String? = null,
        url: String? = null,
        largeImage: String? = null,
        largeText: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        buttons: List<Pair<String, String>> = emptyList(),
    ): ActivityPayload = ActivityPayload(
        name = name,
        type = type.code,
        applicationId = applicationId,
        details = details,
        state = state,
        url = url,
        largeImage = largeImage,
        largeText = largeText,
        smallImage = smallImage,
        smallText = smallText,
        startMs = startMs,
        endMs = endMs,
        buttons = buttons,
    ).also {
        Timber.tag(TAG).d(
            "buildActivity: name=%s, type=%d, details=%s, state=%s, buttons=%d, hasLargeImage=%s, hasSmallImage=%s",
            name, type.code, details?.take(40), state?.take(40), buttons.size,
            largeImage != null, smallImage != null,
        )
    }

    fun buildPresenceUpdate(
        status: PresenceStatus,
        afk: Boolean = false,
        since: Long = 0,
        activities: List<ActivityPayload>,
    ): String {
        val d = JSONObject()
        d.put("since", since)
        d.put("activities", JSONArray().apply { activities.forEach { put(activityToJson(it)) } })
        d.put("status", status.wire)
        d.put("afk", afk)

        val root = JSONObject()
        root.put("op", 3)
        root.put("d", d)
        val json = root.toString()
        Timber.tag(TAG).v("buildPresenceUpdate: activities=%d, status=%s", activities.size, status.wire)
        return json
    }

    private fun activityToJson(activity: ActivityPayload): JSONObject {
        val obj = JSONObject()
        obj.put("name", activity.name.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS))
        obj.put("type", activity.type)
        activity.applicationId?.let { obj.put("application_id", it) }
        obj.put("created_at", System.currentTimeMillis())
        activity.details?.let { obj.put("details", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
        activity.state?.let { obj.put("state", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
        activity.url.takeIfWithinDiscordFieldLimit(MAX_ACTIVITY_URL_CODE_UNITS)?.let { obj.put("url", it) }

        if (activity.startMs != null || activity.endMs != null) {
            val timestamps = JSONObject()
            activity.startMs?.let { timestamps.put("start", it) }
            activity.endMs?.let { timestamps.put("end", it) }
            obj.put("timestamps", timestamps)
        }

        // Include assets block when image paths are available.
        // These should be resolved via POST /api/v9/applications/{id}/external-assets
        // to get mp:external/<hash> references that Discord's Gateway accepts.
        val largeImage = activity.largeImage.takeIfWithinDiscordFieldLimit(MAX_ASSET_IMAGE_CODE_UNITS)
        val smallImage = activity.smallImage.takeIfWithinDiscordFieldLimit(MAX_ASSET_IMAGE_CODE_UNITS)
        if (largeImage != null || smallImage != null) {
            val assets = JSONObject()
            largeImage?.let { assets.put("large_image", it) }
            activity.largeText?.let { assets.put("large_text", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
            smallImage?.let { assets.put("small_image", it) }
            activity.smallText?.let { assets.put("small_text", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
            obj.put("assets", assets)
        }

        // In Gateway op 3 PRESENCE_UPDATE, buttons is string[] (labels only).
        // URLs are sent in metadata.button_urls so buttons are clickable.
        val validButtons =
            activity.buttons
                .take(MAX_ACTIVITY_BUTTONS)
                .mapNotNull { (label, url) ->
                    url.takeIfWithinDiscordFieldLimit(MAX_ACTIVITY_URL_CODE_UNITS)?.let { safeUrl ->
                        label.truncateDiscordField(MAX_BUTTON_LABEL_CODE_UNITS) to safeUrl
                    }
                }
        if (validButtons.isNotEmpty()) {
            val arr = JSONArray()
            val urlArr = JSONArray()
            validButtons.forEach { (label, url) ->
                arr.put(label)
                if (url.isNotEmpty()) urlArr.put(url)
            }
            obj.put("buttons", arr)
            if (urlArr.length() > 0) {
                val metadata = JSONObject()
                metadata.put("button_urls", urlArr)
                obj.put("metadata", metadata)
            }
        }

        return obj
    }

    private fun String?.takeIfWithinDiscordFieldLimit(maxCodeUnits: Int): String? =
        this?.takeIf { it.length <= maxCodeUnits }

    private fun String.truncateDiscordField(maxCodeUnits: Int): String {
        if (length <= maxCodeUnits) return this
        // Discord validates lengths by UTF-16 code units; avoid splitting a surrogate pair.
        val end = if (this[maxCodeUnits - 1] in '\uD800'..'\uDBFF') maxCodeUnits - 1 else maxCodeUnits
        return substring(0, end)
    }

    private const val TAG = "DiscordSvc"
}
