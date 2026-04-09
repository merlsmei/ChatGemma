package com.chatgemma.app.domain.usecase.model

import com.chatgemma.app.data.repository.ModelRepository
import javax.inject.Inject

class SetActiveModelUseCase @Inject constructor(
    private val modelRepository: ModelRepository
) {
    suspend operator fun invoke(modelId: String) {
        modelRepository.setActiveModel(modelId)
    }
}
