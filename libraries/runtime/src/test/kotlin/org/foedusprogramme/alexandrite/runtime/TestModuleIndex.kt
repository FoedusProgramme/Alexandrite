package org.foedusprogramme.alexandrite.runtime

import org.foedusprogramme.alexandrite.sdk.di.Binding
import org.foedusprogramme.alexandrite.sdk.di.ModuleIndex

class TestModuleIndex : ModuleIndex {
    override val module: String = "runtime-test"

    override fun bindings(): List<Binding<*>> = emptyList()
}
