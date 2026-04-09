package com.chatgemma.app.domain.usecase.model

import com.chatgemma.app.data.repository.ModelRepository
import com.chatgemma.app.domain.model.ModelVersion
import javax.inject.Inject

class CheckModelUpdatesUseCase @Inject constructor(
    private val modelRepository: ModelRepository
) {
    suspend operator fun invoke(): List<ModelVersion> =
        modelRepository.checkForUpdates()
}
