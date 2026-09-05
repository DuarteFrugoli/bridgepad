package dev.jonalakas.bridgepad.localization

import android.content.Context
import androidx.annotation.StringRes

/** Keep resource identity in session state so notices follow locale changes. */
data class LocalizedMessage(
    @param:StringRes val resourceId: Int,
    val arguments: List<Any>,
) {
    constructor(@StringRes resourceId: Int, vararg arguments: Any) : this(resourceId, arguments.toList())

    fun resolve(context: Context): String = context.getString(resourceId, *arguments.toTypedArray())
}
