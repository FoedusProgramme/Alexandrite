package org.unitedsoftware.alexandrite.internal.path

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTest {
    @Test
    fun `a leading tilde alone or before a slash is the home directory`() {
        assertEquals("/home/user", expandHome("~", "/home/user"))
        assertEquals("/home/user/notes", expandHome("~/notes", "/home/user"))
        assertEquals("~other/notes", expandHome("~other/notes", "/home/user"))
        assertEquals("a/~/b", expandHome("a/~/b", "/home/user"))
        assertEquals("~/notes", expandHome("~/notes", null))
        assertEquals("~/notes", expandHome("~/notes", ""))
    }
}
