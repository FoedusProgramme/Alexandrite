package org.foedusprogramme.alexandrite.sdk.di

/** An instance its container starts once the whole graph is created. */
public interface Startable {
    public suspend fun start()
}
