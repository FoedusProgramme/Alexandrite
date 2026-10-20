package org.unitedsoftware.alexandrite.internal.tool

/** Why a tool does not run a call, told to the model; [blocked] refuses it without asking anyone. */
public class ToolRefusal(override val message: String, public val blocked: Boolean = false) : Exception(message)
