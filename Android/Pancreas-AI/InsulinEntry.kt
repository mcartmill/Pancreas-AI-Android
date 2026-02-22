package com.pancreas.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

enum class InsulinType(val label: String) {
    RAPID("Rapid-acting"),
    LONG("Long-acting"),
    OTHER("Other")
}

data class InsulinEntry(
    val id: String = UUID.randomUUID().toString(),
    val units: Double,
    val type: InsulinType,
    val timestampMs: Long = System.currentTimeMillis(),
    val note: String = ""
)

object InsulinManager {

    private const val FILE_NAME = "insulin_log.json"
    private val gson = Gson()

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): List<InsulinEntry> {
        return try {
            val text = file(ctx).takeIf { it.exists() }?.readText() ?: return emptyList()
            val type = object : TypeToken<List<InsulinEntryJson>>() {}.type
            val raw: List<InsulinEntryJson> = gson.fromJson(text, type) ?: emptyList()
            raw.map { it.toEntry() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, entries: List<InsulinEntry>) {
        try {
            val json = gson.toJson(entries.map { InsulinEntryJson.from(it) })
            file(ctx).writeText(json)
        } catch (_: Exception) {}
    }

    fun add(ctx: Context, entry: InsulinEntry): List<InsulinEntry> {
        val updated = (load(ctx) + entry).sortedByDescending { it.timestampMs }
        save(ctx, updated)
        return updated
    }

    fun delete(ctx: Context, id: String): List<InsulinEntry> {
        val updated = load(ctx).filter { it.id != id }
        save(ctx, updated)
        return updated
    }

    /** Returns entries within the given time window, newest first */
    fun forWindow(ctx: Context, fromMs: Long, toMs: Long): List<InsulinEntry> =
        load(ctx).filter { it.timestampMs in fromMs..toMs }

    // Gson-safe flat representation (enum as String)
    private data class InsulinEntryJson(
        val id: String,
        val units: Double,
        val type: String,
        val timestampMs: Long,
        val note: String
    ) {
        fun toEntry() = InsulinEntry(
            id = id,
            units = units,
            type = try { InsulinType.valueOf(type) } catch (_: Exception) { InsulinType.OTHER },
            timestampMs = timestampMs,
            note = note
        )
        companion object {
            fun from(e: InsulinEntry) = InsulinEntryJson(e.id, e.units, e.type.name, e.timestampMs, e.note)
        }
    }
}
