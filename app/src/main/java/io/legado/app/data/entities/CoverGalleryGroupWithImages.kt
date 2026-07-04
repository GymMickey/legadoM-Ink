package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Relation

data class CoverGalleryGroupWithImages(
    @Embedded
    val group: CoverGalleryGroup,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId"
    )
    val images: List<CoverGalleryImage>
)
