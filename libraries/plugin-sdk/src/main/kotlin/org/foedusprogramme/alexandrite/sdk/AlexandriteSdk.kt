package org.foedusprogramme.alexandrite.sdk

/**
 * Constants of the plugin SDK.
 *
 * How the SDK grows without breaking the plugins built against it:
 * - a type with a builder (`builder(…)`, `toBuilder()`, `rebuild { }`) gains optional properties;
 * - an interface marked `@SubclassOptInRequired(InternalAlexandriteApi::class)` is implemented by Alexandrite alone
 *   and gains members and subtypes, so a `when` over its subtypes needs an `else` branch;
 * - an open enumeration, a value class with companion constants, gains constants, so a `when` over it needs an `else`
 *   branch;
 * - an enum whose values only plugins produce may gain constants, so a `when` over it needs an `else` branch too;
 * - an interface that plugins implement gains members only with default bodies;
 * - the stored JSON of transcript entries gains types and fields, and a version reads a type it does not know as its
 *   `Unknown` form;
 * - every other change raises [API_VERSION], and a runtime loads only plugins built against its own.
 */
public object AlexandriteSdk {
    /** Raised on every incompatible change of the plugin API. */
    public const val API_VERSION: Int = 1
}
