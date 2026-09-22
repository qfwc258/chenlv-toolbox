package com.wb.mdgw.catalog

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 目录文书的跨次使用偏好（JSON 持久化）。
 *
 * 只记「换案件也基本不变」的设置：标题、提交人、证据目录补空行数；
 * 证据条目与归档页码属于一次性案件数据，不持久化。
 */
@Serializable
data class CatalogPrefsData(
    val evidenceTitle: String = "证 据 目 录",
    val submitter: String = "",
    val minRows: Int = 10,
    val archiveTitle: String = "宁乡市法律援助案卷归档目录"
)

object CatalogPrefs : JsonFileStore<CatalogPrefsData>() {
    override val fileName: String = "catalog_prefs.json"
    override fun serializer() = CatalogPrefsData.serializer()

    fun load(context: Context): CatalogPrefsData = read(context) ?: CatalogPrefsData()

    fun save(context: Context, data: CatalogPrefsData) {
        write(context, data)
    }
}
