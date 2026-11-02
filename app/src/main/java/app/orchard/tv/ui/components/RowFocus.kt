package app.orchard.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Which row of a panel the cursor was in last, so that leaving a side rail to the right returns to
 * that row rather than to the top of the section (the owner, 2.23).
 *
 * A row is a label and a control, and only the control can take the focus: so a row publishes its
 * name through [LocalRowKey], the control it holds registers itself here, and the panel asks for the
 * last row's control by name. Walking the rail forgets it — another section then has the cursor.
 */
class RowBook {
    val requesters = mutableMapOf<String, FocusRequester>()

    /** The row holding the cursor, as its [LocalRowKey]; null before the first row is reached. */
    var last: String? by mutableStateOf(null)

    fun put(key: String, requester: FocusRequester) {
        requesters[key] = requester
    }

    /** Only the control still standing for that row may unregister it: a recomposed row replaces it. */
    fun drop(key: String, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
    }

    fun focusOf(key: String): FocusRequester? = requesters[key]
}

val LocalRowBook = compositionLocalOf<RowBook?> { null }

/** The name of the row a control sits in; null for a control that stands on its own (a card). */
val LocalRowKey = compositionLocalOf<String?> { null }
