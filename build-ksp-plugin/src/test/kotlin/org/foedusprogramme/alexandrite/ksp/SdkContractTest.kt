package org.foedusprogramme.alexandrite.ksp

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.AlexandriteSdk
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.ConfigSectionSpec
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.di.Named
import org.foedusprogramme.alexandrite.sdk.di.PluginLocal
import org.foedusprogramme.alexandrite.sdk.di.Provides
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.di.container.Binding
import org.foedusprogramme.alexandrite.sdk.di.container.Dependency
import org.foedusprogramme.alexandrite.sdk.di.container.DependencyKind
import org.foedusprogramme.alexandrite.sdk.di.container.Scope
import org.foedusprogramme.alexandrite.sdk.hook.Hook
import org.foedusprogramme.alexandrite.sdk.plugin.Plugin
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIndex
import org.foedusprogramme.alexandrite.sdk.plugin.PluginInfo
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
            PluginLocal::class to PLUGIN_LOCAL,
            Plugin::class to PLUGIN,
            ConfigSection::class to CONFIG_SECTION,
            Serializable::class to SERIALIZABLE,
        )
    }

    @Test
    fun `the types the generated index refers to are the SDK's`() {
        assertNames(
            AlexandriteSdk::class to ALEXANDRITE_SDK,
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
    fun `the id rules are the SDK's`() {
        assertEquals(PluginIds.PATTERN.pattern, PLUGIN_ID.pattern)
        assertEquals(PluginIds.RESERVED_PREFIX, RESERVED_PREFIX)
        assertEquals(PluginIds.THIRD_PARTY_ROOT, THIRD_PARTY_ROOT)
        assertEquals(PluginIds.ENABLED_KEY, ENABLED)
        assertEquals(PluginIds.INSTANCES_KEY, INSTANCES)
    }

    @Test
    fun `the descriptor's SDK API version is the SDK's`() {
        assertEquals(AlexandriteSdk.API_VERSION, SDK_API_VERSION)
    }

    @Test
    fun `the service file and the descriptor lie where the SDK says`() {
        assertEquals(PluginIndex.SERVICE_FILE, SERVICE_FILE)
        assertEquals(PluginIndex.descriptorPath("sample"), "$DESCRIPTOR_DIRECTORY/sample.$DESCRIPTOR_EXTENSION")
    }
}
