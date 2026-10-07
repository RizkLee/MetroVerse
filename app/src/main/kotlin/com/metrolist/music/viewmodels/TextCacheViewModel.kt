package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import com.metrolist.music.cache.TextCacheClearTarget
import com.metrolist.music.cache.TextCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TextCacheViewModel @Inject constructor(private val cache: TextCacheManager) : ViewModel() {
    val state = cache.state
    suspend fun refresh() = cache.refresh()
    suspend fun setLimit(limitMiB: Int) = cache.setLimit(limitMiB)
    suspend fun clear(target: TextCacheClearTarget, includeUnverified: Boolean) = cache.clear(target, includeUnverified)
}
