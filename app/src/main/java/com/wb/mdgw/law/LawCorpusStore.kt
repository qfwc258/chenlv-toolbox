package com.wb.mdgw.law

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 离线法规库 / 核对清单加载与常看法条收藏持久化。
 * 语料来自参照源码 public/data/laws-offline.json 与 src/data/checklists.ts。
 */
object LawCorpusStore {

    private const val PREFS = "law_corpus_prefs"
    private const val KEY_STARRED = "starred_laws"
    private const val ASSET_CORPUS = "law/laws-offline.json"
    private const val ASSET_CHECKLISTS = "law/practice-checklists.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedCorpus: StatuteCorpus? = null

    @Volatile
    private var cachedEntries: List<StatuteEntry>? = null

    @Volatile
    private var cachedChecklists: List<PracticeChecklist>? = null

    fun loadCorpus(context: Context): StatuteCorpus {
        cachedCorpus?.let { return it }
        val text = context.assets.open(ASSET_CORPUS).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val corpus = json.decodeFromString<StatuteCorpus>(text)
        cachedCorpus = corpus
        return corpus
    }

    fun entries(context: Context): List<StatuteEntry> {
        cachedEntries?.let { return it }
        val list = StatuteEngine.buildEntries(loadCorpus(context))
        cachedEntries = list
        return list
    }

    fun checklists(context: Context): List<PracticeChecklist> {
        cachedChecklists?.let { return it }
        val text = context.assets.open(ASSET_CHECKLISTS).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val list = json.decodeFromString<List<PracticeChecklist>>(text)
        cachedChecklists = list
        return list
    }

    fun dataAsOf(context: Context): String = loadCorpus(context).dataAsOf.ifBlank { "2026-11-24" }

    fun getStarred(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STARRED, null) ?: return DEFAULT_STARRED
        return runCatching {
            json.decodeFromString<List<String>>(raw).toSet()
        }.getOrDefault(DEFAULT_STARRED)
    }

    fun saveStarred(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STARRED, json.encodeToString(set.toList())).apply()
    }

    fun toggleStarred(context: Context, key: String): Set<String> {
        val current = getStarred(context).toMutableSet()
        if (!current.add(key)) current.remove(key)
        saveStarred(context, current)
        return current
    }

    private val DEFAULT_STARRED = setOf("civil:585", "civil:561", "civil:577", "civil:1195")
}
