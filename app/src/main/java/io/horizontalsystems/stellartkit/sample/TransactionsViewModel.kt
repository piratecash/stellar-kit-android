package io.horizontalsystems.stellartkit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.stellarkit.TagQuery
import io.horizontalsystems.stellarkit.room.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TransactionsViewModel : ViewModel() {
    private val kit = App.kit
    private var operations: List<Operation>? = null
    private val tagQuery = TagQuery(null, null, null)
    private var page = 1
    private val reloadMutex = Mutex()

    var uiState by mutableStateOf(
        EventsUiState(
            operations = operations
        )
    )
        private set

    init {
        viewModelScope.launch(Dispatchers.Default) {
            kit.operationFlow(tagQuery).collect {
                reloadEvents()
            }
        }

        viewModelScope.launch { reloadEvents() }
    }

    fun onBottomReached() {
        page++
        viewModelScope.launch { reloadEvents() }
    }

    private suspend fun reloadEvents() {
        reloadMutex.withLock {
            operations = kit.operationsBefore(tagQuery, limit = 10 * page)
            emitState()
        }
    }

    private fun emitState() {
        viewModelScope.launch {
            uiState = EventsUiState(
                operations = operations
            )
        }
    }
}


data class EventsUiState(val operations: List<Operation>?)