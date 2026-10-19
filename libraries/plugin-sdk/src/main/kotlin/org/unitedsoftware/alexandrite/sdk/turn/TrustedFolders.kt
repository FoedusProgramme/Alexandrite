package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import java.nio.file.Path
import java.time.Instant

/**
 * The folders whose project instructions load, changed without a permission check, recording who asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TrustedFolders {
    /** The trusted folders, each covering the folders below it outside the repositories nested in it. */
    public fun list(): List<TrustedFolder>

    /**
     * Trusts the folder [folder] (absolute), so that the project instructions in it and below it load outside the
     * repositories nested in it, and returns the trusted folder that covers it, an ancestor when one covers it already.
     */
    public suspend fun trust(folder: Path, by: ChatUser?): Path

    /**
     * Stops trusting [folder] at runtime, removing its grant whenever there is one, and tells whether the config file
     * still trusts it.
     */
    public suspend fun untrust(folder: Path, by: ChatUser?): Revocation
}

/** A trusted folder. */
@Poko
public class TrustedFolder(
    public val path: Path,
    /** Whether the config file declares the folder. */
    public val declared: Boolean,
    /** When the folder was trusted at runtime, null when it was not. */
    public val trustedAt: Instant?,
    /** Who trusted the folder at runtime, null for the host or when nobody did. */
    public val trustedBy: ChatUser?,
)
