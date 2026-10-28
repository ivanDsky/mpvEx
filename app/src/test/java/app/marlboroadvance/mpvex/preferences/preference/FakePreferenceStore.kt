package app.marlboroadvance.mpvex.preferences.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [PreferenceStore] for JVM unit tests.
 * Same-key lookups return the same [Preference] so state stays shared.
 */
class FakePreferenceStore : PreferenceStore {
  private val values = mutableMapOf<String, Any?>()
  private val preferences = mutableMapOf<String, Preference<*>>()

  @Suppress("UNCHECKED_CAST")
  private fun <T> preference(key: String, defaultValue: T): Preference<T> =
    preferences.getOrPut(key) { FakePreference(values, key, defaultValue) } as Preference<T>

  override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)

  override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)

  override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)

  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    preference(key, defaultValue)

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = preference(key, defaultValue)

  override fun getAll(): Map<String, *> = values.toMap()
}

private class FakePreference<T>(
  private val values: MutableMap<String, Any?>,
  private val key: String,
  private val defaultValue: T,
) : Preference<T> {
  private val flow = MutableStateFlow(defaultValue)

  override fun key(): String = key

  override fun get(): T = flow.value

  override fun set(value: T) {
    values[key] = value
    flow.value = value
  }

  override fun isSet(): Boolean = values.containsKey(key)

  override fun delete() {
    values.remove(key)
    flow.value = defaultValue
  }

  override fun defaultValue(): T = defaultValue

  override fun changes(): Flow<T> = flow

  override fun stateIn(scope: CoroutineScope): StateFlow<T> = flow
}
