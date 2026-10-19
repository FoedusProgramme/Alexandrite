package org.unitedsoftware.alexandrite.channel.telegram.pairing

import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.Pairings
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import java.time.Clock

/** The pairing of the chats of every Telegram channel instance. */
@Singleton
@Contribute(PairingDesk::class)
internal class TelegramPairingDesk(clock: Clock, records: Records) : Pairings(clock, records)
