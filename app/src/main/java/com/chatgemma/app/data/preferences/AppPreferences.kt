package com.chatgemma.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.chatgemma.app.domain.model.InferenceParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // Inference params
    val temperature: Flow<Float> = dataStore.data.map { it[Keys.TEMPERATURE] ?: 0.8f }
    val maxTokens: Flow<Int> = dataStore.data.map { it[Keys.MAX_TOKENS] ?: 1024 }
    val topK: Flow<Int> = dataStore.data.map { it[Keys.TOP_K] ?: 40 }
    val topP: Flow<Float> = dataStore.data.map { it[Keys.TOP_P] ?: 0.95f }
    val activeModelId: Flow<String> = dataStore.data.map { it[Keys.ACTIVE_MODEL_ID] ?: "" }
    val gpuLayers: Flow<Int> = dataStore.data.map { it[Keys.GPU_LAYERS] ?: 0 }

    // UI prefs
    val isDarkTheme: Flow<Boolean> = dataStore.data.map { it[Keys.IS_DARK_THEME] ?: true }
    val lastSessionId: Flow<String> = dataStore.data.map { it[Keys.LAST_SESSION_ID] ?: "" }
    val lastBranchId: Flow<String> = dataStore.data.map { it[Keys.LAST_BRANCH_ID] ?: "" }

    // HuggingFace check
    val lastHfCheck: Flow<Long> = dataStore.data.map { it[Keys.LAST_HF_CHECK] ?: 0L }

    suspend fun saveInferenceParams(params: InferenceParams) {
        dataStore.edit {
            it[Keys.TEMPERATURE] = params.temperature
            it[Keys.MAX_TOKENS] = params.maxTokens
            it[Keys.TOP_K] = params.topK
            it[Keys.TOP_P] = params.topP
            it[Keys.ACTIVE_MODEL_ID] = params.modelId
            it[Keys.GPU_LAYERS] = params.gpuLayers
        }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[Keys.IS_DARK_THEME] = dark }
    }

    suspend fun setLastSession(sessionId: String, branchId: String) {
        dataStore.edit {
            it[Keys.LAST_SESSION_ID] = sessionId
            it[Keys.LAST_BRANCH_ID] = branchId
        }
    }

    suspend fun setLastHfCheck(time: Long) {
        dataStore.edit { it[Keys.LAST_HF_CHECK] = time }
    }

    /** Set before GPU inference starts; cleared after success. */
    suspend fun setGpuSentinel(active: Boolean) {
        dataStore.edit { it[Keys.GPU_SENTINEL] = active }
    }

    /**
     * If the GPU sentinel is still set from a previous run, the app crashed
     * during GPU inference. Reset gpuLayers to 0 and clear the sentinel.
     * Returns true if a crash was detected and GPU was disabled.
     */
    suspend fun checkAndResetGpuCrash(): Boolean {
        val prefs = dataStore.data.first()
        if (prefs[Keys.GPU_SENTINEL] == true) {
            dataStore.edit {
                it[Keys.GPU_LAYERS] = 0
                it[Keys.GPU_SENTINEL] = false
            }
            return true
        }
        return false
    }

    private object Keys {
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = floatPreferencesKey("top_p")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val LAST_BRANCH_ID = stringPreferencesKey("last_branch_id")
        val GPU_LAYERS = intPreferencesKey("gpu_layers")
        val GPU_SENTINEL = booleanPreferencesKey("gpu_sentinel")
        val LAST_HF_CHECK = longPreferencesKey("last_hf_check")
    }
}
