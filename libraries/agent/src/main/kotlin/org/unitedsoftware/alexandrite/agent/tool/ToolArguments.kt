package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.tool.CallReview

/** The string argument [name], null when it is missing or anything else. */
internal fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The review of a call that this refusal stops: refused as a protected place is when [ToolRefusal.blocked]. */
internal fun ToolRefusal.review(): CallReview =
    CallReview.builder().apply { if (blocked) refusal(message) else problem(message) }.build()
