package org.unitedsoftware.alexandrite.agent.config

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/** The models that an agent hands its side work to, each `<endpoint>/<model>`. */
@Serializable
internal class RolesConfig(
    /** The judge of `auto` mode, the agent's model when null. */
    val judge: String? = null,
    /** The writer of compactions' summaries, the turn's model when null. */
    val summary: String? = null,
    /** The answerer of questions about web pages, the judge when null. */
    val web: String? = null,
    /** The reader of images for a turn whose model does not take them, none when null. */
    val image: String? = null,
    /** The reader of audio, which transcribes it, for a turn whose model does not take it, none when null. */
    val audio: String? = null,
    /** The reader of video for a turn whose model does not take it, none when null. */
    val video: String? = null,
    /** The reader of documents and other files for a turn whose model does not take them, none when null. */
    val file: String? = null,
) {
    /** The reader of media of [kind], null for none or a kind without a role. */
    fun reader(kind: MediaKind): String? = when (kind) {
        MediaKind.IMAGE -> image
        MediaKind.AUDIO -> audio
        MediaKind.VIDEO -> video
        MediaKind.FILE -> file
        else -> null
    }

    /** Each role that is set, by its key. */
    fun set(): Map<String, String> = listOfNotNull(
        judge?.let { "judge" to it },
        summary?.let { "summary" to it },
        web?.let { "web" to it },
        image?.let { "image" to it },
        audio?.let { "audio" to it },
        video?.let { "video" to it },
        file?.let { "file" to it },
    ).toMap()
}

/**
 * How an agent's turns take media of each kind: `auto`, `native`, `delegate` or `off`, as [MediaHandling] describes
 * them.
 */
@Serializable
internal class MediaConfig(
    val image: String = MediaHandling.AUTO.id,
    val audio: String = MediaHandling.AUTO.id,
    val video: String = MediaHandling.AUTO.id,
    val file: String = MediaHandling.AUTO.id,
) {
    /** The handling of media of [kind], `auto` for a kind without a key. */
    fun handling(kind: MediaKind): MediaHandling = MediaHandling.of(
        when (kind) {
            MediaKind.IMAGE -> image
            MediaKind.AUDIO -> audio
            MediaKind.VIDEO -> video
            MediaKind.FILE -> file
            else -> MediaHandling.AUTO.id
        },
    ) ?: MediaHandling.AUTO

    /** Each kind's key and value. */
    fun entries(): Map<String, String> = mapOf("image" to image, "audio" to audio, "video" to video, "file" to file)
}

/** How a turn takes media of one kind. */
internal enum class MediaHandling(val id: String) {
    /** The turn's model when it takes the kind, else the kind's reader role, else only the saved file. */
    AUTO("auto"),

    /** The turn's model when it takes the kind, else only the saved file. */
    NATIVE("native"),

    /** The kind's reader role, also where the turn's model takes the kind; the config must set that role. */
    DELEGATE("delegate"),

    /** Only the saved file. */
    OFF("off"),
    ;

    companion object {
        fun of(id: String): MediaHandling? = entries.firstOrNull { it.id == id }
    }
}
