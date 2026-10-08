package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/** What a model needs to continue its own reasoning, replayed only to the model that made it. */
@Poko
public class ReasoningSeal(
    public val origin: ModelRef,
    public val dialect: Dialect,
    public val kind: SealKind,
    public val data: String,
    public val providerData: ProviderData = ProviderData.EMPTY,
) {
    override fun toString(): String =
        "ReasoningSeal(origin=$origin, dialect=$dialect, kind=$kind, data=<${data.length} chars>)"
}

/** What a [ReasoningSeal] holds. */
@JvmInline
@Serializable
public value class SealKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A signature over reasoning text given in the clear. */
        public val SIGNATURE: SealKind = SealKind("signature")

        /** Reasoning the provider withheld from the client. */
        public val REDACTED: SealKind = SealKind("redacted")

        /** Reasoning encrypted for the provider. */
        public val ENCRYPTED: SealKind = SealKind("encrypted")

        /** Reasoning text the provider wants back as it was. */
        public val PLAIN: SealKind = SealKind("plain")
    }
}
