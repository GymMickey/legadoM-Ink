package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_recycle_bin",
    indices = [
        Index(value = ["type"]),
        Index(value = ["key"]),
        Index(value = ["expireAt"])
    ]
)
data class SourceRecycleBin(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var type: String = "",
    var key: String = "",
    var name: String = "",
    var groupName: String? = null,
    var payload: String = "",
    var deletedAt: Long = 0,
    var expireAt: Long = 0
)

