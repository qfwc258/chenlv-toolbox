package com.wb.mdgw.law

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LawSearchUiState(
    val keyword: String = "",
    val results: List<Law> = emptyList(),
    val total: Int = 0,
    val currentPage: Int = 1,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

class LawSearchViewModel(
    private val parser: LawWebParser
) : ViewModel() {

    private val _uiState = MutableStateFlow(LawSearchUiState())
    val uiState: StateFlow<LawSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** 更新关键词并防抖搜索 */
    fun onKeywordChange(keyword: String) {
        _uiState.value = _uiState.value.copy(keyword = keyword)
        searchJob?.cancel()
        if (keyword.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                total = 0,
                hasSearched = false,
                error = null
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // 防抖
            performSearch(page = 1)
        }
    }

    /** 立即搜索（点击搜索按钮） */
    fun searchNow() {
        if (_uiState.value.keyword.isNotBlank()) {
            performSearch(page = 1)
        }
    }

    /** 加载更多 */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isLoading) return
        val maxPage = (state.total + LawConstants.PAGE_SIZE - 1) / LawConstants.PAGE_SIZE
        if (state.currentPage >= maxPage) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            val nextPage = state.currentPage + 1
            val result = parser.search(
                keyword = state.keyword,
                page = nextPage,
                pageSize = LawConstants.PAGE_SIZE
            )
            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        results = _uiState.value.results + result.data.first,
                        total = result.data.second,
                        currentPage = nextPage,
                        isLoadingMore = false,
                        error = null
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoadingMore = false
                    )
                }
                is Result.Loading -> {}
            }
        }
    }

    private fun performSearch(page: Int) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                hasSearched = true
            )
            val result = parser.search(
                keyword = state.keyword,
                page = page,
                pageSize = LawConstants.PAGE_SIZE
            )
            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        results = result.data.first,
                        total = result.data.second,
                        currentPage = page,
                        isLoading = false,
                        error = null
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoading = false,
                        results = emptyList()
                    )
                }
                is Result.Loading -> {}
            }
        }
    }

    companion object {
        fun createFactory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LawSearchViewModel(
                        LawWebParser.getInstance(context.applicationContext)
                    ) as T
                }
            }
        }
    }
}
