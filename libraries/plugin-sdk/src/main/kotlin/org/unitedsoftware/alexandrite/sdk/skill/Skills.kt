package org.unitedsoftware.alexandrite.sdk.skill

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/** The skills a plugin brings, as trusted as the plugin itself; the agent asks for them at each turn's start. */
@ContributedSpi
public interface SkillSource {
    public fun skills(): List<SkillSpec>
}

/** A skill: instructions for one kind of task, which an agent loads when its [description] fits the task at hand. */
@Poko
public class SkillSpec(
    /** Lowercase letters, digits and `-`, 64 characters at most, as the Agent Skills format names a skill. */
    public val name: String,
    /** When to use the skill, 1024 characters at most. */
    public val description: String,
    public val instructions: String,
) {
    init {
        require(NAME.matches(name)) { "A skill's name is lowercase letters, digits and '-', 64 at most: '$name'." }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION) {
            "The description of skill '$name' is 1 to $MAX_DESCRIPTION characters."
        }
        require(instructions.isNotBlank()) { "Skill '$name' gives no instructions." }
    }

    public companion object {
        /** The names a skill may have. */
        public val NAME: Regex = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")

        public const val MAX_DESCRIPTION: Int = 1024
    }
}
