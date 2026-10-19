package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.project.FolderTrust
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolders
import java.nio.file.Path

@Singleton
@Binds(TrustedFolders::class)
internal class TrustedFoldersImpl(private val runner: ControlRunner, private val trust: FolderTrust) : TrustedFolders {
    override fun list(): List<TrustedFolder> = trust.folders()

    override suspend fun trust(folder: Path, by: ChatUser?): Path = runner.refusing(null) { trust.trust(folder, by) }

    override suspend fun untrust(folder: Path, by: ChatUser?): Revocation =
        runner.refusing(null) { trust.untrust(folder, by) }
}
