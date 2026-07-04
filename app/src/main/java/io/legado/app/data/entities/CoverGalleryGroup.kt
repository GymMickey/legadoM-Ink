package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cover_gallery_groups")
data class CoverGalleryGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val isDefault: Boolean = false,
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
