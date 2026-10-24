package org.unitedsoftware.alexandrite.agent.tool

import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import java.nio.file.Path

/** The file that this preview shows a change of, null when it shows none or names it by a relative path. */
internal val ReviewPreview?.writtenFile: Path? get() = (this as? ReviewPreview.Diff)?.path?.takeIf { it.isAbsolute }
