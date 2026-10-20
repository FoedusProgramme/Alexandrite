package org.unitedsoftware.alexandrite.ksp

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.BoundSpi
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Named
import org.unitedsoftware.alexandrite.sdk.di.PluginLocal
import org.unitedsoftware.alexandrite.sdk.di.Provides
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.plugin.Plugin
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkContractTest {
    private fun assertNames(vararg pairs: Pair<KClass<*>, String>) {
        for ((type, name) in pairs) assertEquals(type.qualifiedName, name)
    }

    @Test
    fun `the annotations the processor reads are the SDK's`() {
        assertNames(
            Singleton::class to SINGLETON,
            ChannelInstanceScoped::class to CHANNEL_INSTANCE_SCOPED,
            Inject::class to INJECT,
            Named::class to NAMED,
            Binds::class to BINDS,
            Contribute::class to CONTRIBUTE,
            Provides::class to PROVIDES,
            ContributedSpi::class to CONTRIBUTED_SPI,
            BoundSpi::class to BOUND_SPI,
            PluginLocal::class to PLUGIN_LOCAL,
            Plugin::class to PLUGIN,
            ConfigSection::class to CONFIG_SECTION,
            Serializable::class to SERIALIZABLE,
        )
    }

    @Test
    fun `the annotation arguments the processor reads are the SDK's`() {
        assertEquals(Contribute::name.name, CONTRIBUTION_NAME)
        assertEquals(ContributedSpi::named.name, NAMED_SPI)
    }

    @Test
    fun `the types the generated index refers to are the SDK's`() {
        assertNames(
            InternalAlexandriteApi::class to INTERNAL_API,
            ConfigSectionSpec::class to CONFIG_SECTION_SPEC,
            Binding::class to BINDING,
            Dependency::class to DEPENDENCY,
            DependencyKind::class to DEPENDENCY_KIND,
            Scope::class to SCOPE,
            PluginIndex::class to PLUGIN_INDEX,
            PluginInfo::class to PLUGIN_INFO,
            List::class to LIST,
            Lazy::class to LAZY,
            Unit::class to UNIT,
            String::class to STRING,
            Suppress::class to SUPPRESS,
        )
    }

    @Test
    fun `the SPIs and types the processor checks are the SDK's`() {
        assertNames(Hook::class to HOOK, Channel::class to CHANNEL, ChannelInstance::class to CHANNEL_INSTANCE)
    }

    @Test
    fun `the API version the index records is the SDK's`() {
        assertEquals(AlexandriteSdk.API_VERSION, SDK_API_VERSION)
    }

    @Test
    fun `the id rules are the SDK's`() {
        assertEquals(PluginIds.PATTERN.pattern, PLUGIN_ID.pattern)
        assertEquals(PluginIds.PATTERN.pattern, CHANNEL_TYPE.pattern)
        assertEquals(PluginIds.RESERVED_PREFIX, RESERVED_PREFIX)
        assertEquals(PluginIds.THIRD_PARTY_ROOT, THIRD_PARTY_ROOT)
        assertEquals(PluginIds.ENABLED_KEY, ENABLED)
        assertEquals(PluginIds.INSTANCES_KEY, INSTANCES)
    }
}
