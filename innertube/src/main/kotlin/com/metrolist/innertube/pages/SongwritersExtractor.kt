/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts songwriter credits from InnerTube `MPTC<videoId>` song-credits browse responses.
 *
 * Keeping credit extraction in `:innertube` allows in-memory resolution at display time
 * without altering the Room database schema.
 */
object SongwritersExtractor {
    /**
     * Parses distinct songwriter names from [browseResponse], ignoring performer/producer/provider
     * credit sections across localized YouTube Music headings.
     */
    fun extractSongwriters(browseResponse: JsonObject): List<String> {
        val sections = findSections(browseResponse) ?: return emptyList()
        val writers = mutableListOf<String>()
        for (sectionElement in sections) {
            val sectionObj = (sectionElement as? JsonObject) ?: continue
            val contentSection = sectionObj["dismissableDialogContentSectionRenderer"]?.jsonObject ?: sectionObj
            val titleObj = contentSection["title"] as? JsonObject
            val titleText =
                titleObj
                    ?.get("runs")
                    ?.let { it as? JsonArray }
                    ?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "" }
                    ?: (titleObj?.get("simpleText") as? JsonPrimitive)?.contentOrNull
                    ?: (contentSection["title"] as? JsonPrimitive)?.contentOrNull
                    ?: ""

            if (!isSongwritersTitle(titleText)) continue

            val subtitleObj = contentSection["subtitle"] as? JsonObject
            val runs = subtitleObj?.get("runs") as? JsonArray
            if (runs != null) {
                for (run in runs) {
                    val text = (run as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: continue
                    text.split('\n', ',').forEach { part ->
                        val trimmed = part.trim()
                        if (trimmed.isNotBlank()) {
                            writers.add(trimmed)
                        }
                    }
                }
            } else {
                val simpleText =
                    (subtitleObj?.get("simpleText") as? JsonPrimitive)?.contentOrNull
                        ?: (contentSection["subtitle"] as? JsonPrimitive)?.contentOrNull
                simpleText?.split('\n', ',')?.forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank()) {
                        writers.add(trimmed)
                    }
                }
            }
        }
        return writers.distinct()
    }

    private fun findSections(browseResponse: JsonObject): JsonArray? {
        val actions = browseResponse["onResponseReceivedActions"] as? JsonArray
        if (actions != null) {
            for (action in actions) {
                val popup =
                    (action as? JsonObject)
                        ?.get("openPopupAction")
                        ?.let { it as? JsonObject }
                        ?.get("popup")
                        ?.let { it as? JsonObject } ?: continue
                val dialog =
                    popup["dismissableDialogRenderer"] as? JsonObject
                        ?: popup.values.filterIsInstance<JsonObject>().firstOrNull { it.containsKey("sections") }
                val sections = dialog?.get("sections") as? JsonArray
                if (sections != null) return sections
            }
        }
        val popup = browseResponse["popup"] as? JsonObject
        if (popup != null) {
            val dialog =
                popup["dismissableDialogRenderer"] as? JsonObject
                    ?: popup.values.filterIsInstance<JsonObject>().firstOrNull { it.containsKey("sections") }
            val sections = dialog?.get("sections") as? JsonArray
            if (sections != null) return sections
        }
        return null
    }

    private fun isSongwritersTitle(titleText: String): Boolean {
        val lower = titleText.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower.contains("perform") ||
            lower.contains("produc") ||
            lower.contains("metadata") ||
            lower.contains("provid") ||
            lower.contains("dibawakan") ||
            lower.contains("diproduksi") ||
            lower.contains("interpret") ||
            lower.contains("表演") ||
            lower.contains("演出") ||
            lower.contains("演唱") ||
            lower.contains("制作") ||
            lower.contains("製作") ||
            lower.contains("提供") ||
            lower.contains("来源") ||
            lower.contains("來源")
        ) {
            return false
        }
        return lower.contains("written") ||
            lower.contains("writer") ||
            lower.contains("ditulis") ||
            lower.contains("geschrieben") ||
            lower.contains("écrit") ||
            lower.contains("ecrit") ||
            lower.contains("escrito") ||
            lower.contains("scritto") ||
            lower.contains("schrijver") ||
            lower.contains("geschreven") ||
            lower.contains("auteur") ||
            lower.contains("autor") ||
            lower.contains("autori") ||
            lower.contains("twórcy") ||
            lower.contains("napisane") ||
            lower.contains("yazan") ||
            lower.contains("söz yazarı") ||
            lower.contains("soz yazari") ||
            lower.contains("композитор") ||
            lower.contains("автор") ||
            lower.contains("تأليف") ||
            lower.contains("词曲") ||
            lower.contains("詞曲") ||
            lower.contains("创作者") ||
            lower.contains("創作者") ||
            lower.contains("作詞") ||
            lower.contains("作词") ||
            lower.contains("作曲") ||
            lower.contains("作者") ||
            lower.contains("작사") ||
            lower.contains("작곡") ||
            lower.contains("composer")
    }
}
