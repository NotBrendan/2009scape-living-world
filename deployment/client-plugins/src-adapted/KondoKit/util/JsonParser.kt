package KondoKit.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object JsonParser {
    val gson: Gson = Gson()

    inline fun <reified T> fromJson(json: String): T {
        val type = object : TypeToken<T>() {}.type
        return gson.fromJson(json, type)
    }

    fun <T> fromJson(json: String, classOfT: Class<T>): T = gson.fromJson(json, classOfT)

    fun toJson(src: Any): String = gson.toJson(src)
}
