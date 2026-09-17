package dev.jonalakas.bridgepad.ui.session

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionUiViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()

    fun dispatch(event: SessionUiEvent) {
        mutableState.value = SessionUiReducer.reduce(mutableState.value, event)
    }
}
