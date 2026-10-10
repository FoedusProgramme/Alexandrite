package org.foedusprogramme.alexandrite.channel.telegram.pairing

import org.foedusprogramme.alexandrite.channel.common.pairing.PairingDesk
import org.foedusprogramme.alexandrite.channel.common.pairing.Pairings
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import java.time.Clock

/** The pairing of the chats of every Telegram channel instance. */
@Singleton
@Contribute(PairingDesk::class)
internal class TelegramPairingDesk(clock: Clock) : Pairings(clock)
