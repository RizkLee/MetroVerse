package com.metrolist.music.podcast

import androidx.annotation.StringRes
import com.metrolist.music.R

enum class PodcastCategory(
    val slug: String,
    val appleGenreId: Int,
    @StringRes val titleRes: Int,
) {
    NEWS("news", 1489, R.string.podcast_category_news),
    SOCIETY_AND_CULTURE("society-and-culture", 1324, R.string.podcast_category_society_and_culture),
    EDUCATION("education", 1304, R.string.podcast_category_education),
    COMEDY("comedy", 1303, R.string.podcast_category_comedy),
    TECHNOLOGY("technology", 1318, R.string.podcast_category_technology),
    SCIENCE("science", 1533, R.string.podcast_category_science),
    TRUE_CRIME("true-crime", 1488, R.string.podcast_category_true_crime),
    HEALTH_AND_FITNESS("health-and-fitness", 1512, R.string.podcast_category_health_and_fitness),
    BUSINESS("business", 1321, R.string.podcast_category_business),
    DOCUMENTARY("documentary", 1543, R.string.podcast_category_documentary),
    HISTORY("history", 1487, R.string.podcast_category_history),
    PLACES_AND_TRAVEL("places-and-travel", 1320, R.string.podcast_category_places_and_travel),
    FOOD("food", 1306, R.string.podcast_category_food),
    ARTS("arts", 1301, R.string.podcast_category_arts),
    MUSIC("music", 1310, R.string.podcast_category_music),
    BOOKS("books", 1482, R.string.podcast_category_books),
    SPORTS("sports", 1545, R.string.podcast_category_sports),
    TV_AND_FILM("tv-and-film", 1309, R.string.podcast_category_tv_and_film),
    MENTAL_HEALTH("mental-health", 1517, R.string.podcast_category_mental_health),
    SELF_IMPROVEMENT("self-improvement", 1500, R.string.podcast_category_self_improvement),
    RELATIONSHIPS("relationships", 1544, R.string.podcast_category_relationships),
    RELIGION_AND_SPIRITUALITY("religion-and-spirituality", 1314, R.string.podcast_category_religion_and_spirituality),
    KIDS_AND_FAMILY("kids-and-family", 1305, R.string.podcast_category_kids_and_family),
    ;

    companion object {
        fun fromSlug(slug: String?): PodcastCategory? = entries.firstOrNull { it.slug == slug }
    }
}
