package org.unitedsoftware.alexandrite.agent.tool

import org.unitedsoftware.alexandrite.sdk.tool.Tool

/** The tools a turn [offered] the model, and the [unoffered] ones that its calls may still name. */
internal class Toolset(val offered: List<Tool>, val unoffered: List<Tool>, val callableUnoffered: Set<String>)
