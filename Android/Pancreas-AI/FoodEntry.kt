package com.pancreas.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

enum class MealType(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack"),
    DRINK("Drink"),
    OTHER("Other")
}

data class FoodEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val carbs: Double,           // grams of carbohydrate
    val calories: Int = 0,       // optional
    val mealType: MealType = MealType.OTHER,
    val timestampMs: Long = System.currentTimeMillis(),
    val note: String = ""
)

object FoodManager {

    private const val FILE_NAME = "food_log.json"
    private val gson = Gson()

    fun load(ctx: Context): List<FoodEntry> {
        return try {
            val text = SecureFileStore.read(ctx, FILE_NAME) ?: return emptyList()
            val type = object : TypeToken<List<FoodEntryJson>>() {}.type
            val raw: List<FoodEntryJson> = gson.fromJson(text, type) ?: emptyList()
            raw.map { it.toEntry() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, entries: List<FoodEntry>) {
        try {
            val json = gson.toJson(entries.map { FoodEntryJson.from(it) })
            SecureFileStore.write(ctx, FILE_NAME, json)
        } catch (_: Exception) {}
    }

    fun add(ctx: Context, entry: FoodEntry): List<FoodEntry> {
        val updated = (load(ctx) + entry).sortedByDescending { it.timestampMs }
        save(ctx, updated)
        return updated
    }

    fun delete(ctx: Context, id: String): List<FoodEntry> {
        val updated = load(ctx).filter { it.id != id }
        save(ctx, updated)
        return updated
    }

    fun forWindow(ctx: Context, fromMs: Long, toMs: Long): List<FoodEntry> =
        load(ctx).filter { it.timestampMs in fromMs..toMs }

    // Gson-safe flat representation
    private data class FoodEntryJson(
        val id: String,
        val name: String,
        val carbs: Double,
        val calories: Int,
        val mealType: String,
        val timestampMs: Long,
        val note: String
    ) {
        fun toEntry() = FoodEntry(
            id          = id,
            name        = name,
            carbs       = carbs,
            calories    = calories,
            mealType    = try { MealType.valueOf(mealType) } catch (_: Exception) { MealType.OTHER },
            timestampMs = timestampMs,
            note        = note
        )
        companion object {
            fun from(e: FoodEntry) = FoodEntryJson(
                e.id, e.name, e.carbs, e.calories, e.mealType.name, e.timestampMs, e.note
            )
        }
    }
}
