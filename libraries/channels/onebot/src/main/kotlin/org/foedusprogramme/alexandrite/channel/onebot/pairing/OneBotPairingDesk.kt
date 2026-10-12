package org.foedusprogramme.alexandrite.channel.onebot.pairing

import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.Pairings
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.Records
import java.time.Clock

/** The pairing of the chats of every OneBot channel instance. */
@Singleton
@Contribute(PairingDesk::class)
internal class OneBotPairingDesk(clock: Clock, records: Records) : Pairings(clock, records)
