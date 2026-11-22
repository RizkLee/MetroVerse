/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.music.utils.ContentFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
    val chartsPage = _chartsPage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun loadCharts() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            YouTube.getChartsPage()
                .onSuccess { page ->
                    _chartsPage.value = ContentFilter.current(context).filterChartsPage(page)
                }
                .onFailure { e ->
                    _error.value = "Failed to load charts: ${e.message}"
                }
            
            _isLoading.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            _chartsPage.value?.continuation?.let { continuation ->
                _isLoading.value = true
                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        val contentFilter = ContentFilter.current(context)
                        val merged = ChartsPage(
                            sections = _chartsPage.value?.sections.orEmpty() + newPage.sections,
                            continuation = newPage.continuation
                        )
                        _chartsPage.value = contentFilter.filterChartsPage(merged)
                    }
                    .onFailure { e ->
                        _error.value = "Failed to load more: ${e.message}"
                    }
                _isLoading.value = false
            }
        }
    }
}
