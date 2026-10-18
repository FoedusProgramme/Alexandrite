package org.unitedsoftware.alexandrite.sdk.turn

/** A change that [AgentControl] refused, whose message is for the user. */
public class ControlRefusedException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
