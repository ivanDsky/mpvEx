package `is`.xyz.mpv

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for MPVLib: MPVLib is a Kotlin object whose members are
 * native methods backed by the mpv/player JNI libraries, so it cannot load on
 * the JVM. This shadow stands in for those methods and records every mpv
 * property read/write and command, which is the real boundary the app uses
 * to talk to the player.
 */
@Implements(MPVLib::class)
class ShadowMPVLib {
  companion object {
    val intProps = mutableMapOf<String, Int>()
    val stringProps = mutableMapOf<String, String>()
    val boolProps = mutableMapOf<String, Boolean>()
    val commands = mutableListOf<List<String>>()

    fun reset() {
      intProps.clear()
      stringProps.clear()
      boolProps.clear()
      commands.clear()
    }
  }

  @Implementation
  fun getPropertyInt(property: String): Int? = intProps[property] ?: 0

  @Implementation
  fun setPropertyInt(property: String, value: Int) {
    intProps[property] = value
  }

  @Implementation
  fun getPropertyString(property: String): String? = stringProps[property] ?: ""

  @Implementation
  fun setPropertyString(property: String, value: String?) {
    if (value != null) stringProps[property] = value
  }

  @Implementation
  fun getPropertyBoolean(property: String): Boolean? = boolProps[property] ?: false

  @Implementation
  fun setPropertyBoolean(property: String, value: Boolean) {
    boolProps[property] = value
  }

  @Implementation
  fun command(vararg args: String) {
    commands.add(args.toList())
  }
}
