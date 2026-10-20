package org.unitedsoftware.alexandrite.gradle

import org.gradle.api.provider.Property

/** What a third-party plugin's build tells the Alexandrite KSP processor. */
public abstract class AlexandriteExtension {
    /** The plugin id, which also names the plugin's config root `plugins.<id>` */
    public abstract val pluginId: Property<String>

    /** The version the plugin reports, by default the project version */
    public abstract val pluginVersion: Property<String>

    /** The package of the generated index, by default the common package of the plugin's components */
    public abstract val indexPackage: Property<String>

    /** The fully qualified name of the generated index */
    public abstract val indexClass: Property<String>
}
