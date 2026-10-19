package org.unitedsoftware.alexandrite.channel.onebot.pairing

import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.Pairings
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import java.time.Clock

/** The pairing of the chats of every OneBot channel instance. */
@Singleton
@Contribute(PairingDesk::class)
internal class OneBotPairingDesk(clock: Clock, records: Records) : Pairings(clock, records)
