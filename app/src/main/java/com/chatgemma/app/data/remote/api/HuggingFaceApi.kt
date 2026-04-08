package com.chatgemma.app.data.remote.api

import com.chatgemma.app.data.remote.dto.HfModelDto
import retrofit2.http.GET
import retrofit2.http.Query

interface HuggingFaceApi {
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") search: String,
        @Query("sort") sort: String = "lastModified",
        @Query("limit") limit: Int = 20,
        @Query("filter") filter: String? = null,
        @Query("direction") direction: Int = -1,
        @Query("author") author: String? = null
    ): List<HfModelDto>
}
