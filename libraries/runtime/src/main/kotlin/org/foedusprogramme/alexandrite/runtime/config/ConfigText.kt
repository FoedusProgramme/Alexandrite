package org.foedusprogramme.alexandrite.runtime.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown when a path cannot be changed in a [ConfigText]. */
internal class ConfigPathException(message: String) : RuntimeException(message)

/** The text of a valid JSON config file, changed by splices that leave every other character as it is. */
internal class ConfigText(val text: String) {
    private val root: ObjectSpan = Parser(text).root()
    private val newline = if ("\r\n" in text) "\r\n" else "\n"
    private val unit = indentUnit(root) ?: DEFAULT_INDENT

    /** This text with [value] at [path], the members missing on the way created. */
    fun set(path: List<String>, value: JsonElement): ConfigText {
        require(path.isNotEmpty()) { "A path names at least one key." }
        var parent: ObjectSpan? = null
        var target = root
        var index = 0
        while (true) {
            val key = path[index]
            val member = target.members.lastOrNull { it.key == key }
            val rest = path.drop(index + 1).foldRight(value) { name, child -> JsonObject(mapOf(name to child)) }
            if (member == null) return insert(target, multiline(target, parent), key, rest)
            if (index == path.lastIndex || member.value.isNull()) return replace(target, member, rest)
            parent = target
            target = member.value as? ObjectSpan ?: throw noObject(path, index)
            index++
        }
    }

    /** This text without the member at [path], unchanged when there is none. */
    fun remove(path: List<String>): ConfigText {
        require(path.isNotEmpty()) { "A path names at least one key." }
        var target = root
        var index = 0
        while (true) {
            val position = target.members.indexOfLast { it.key == path[index] }
            if (position < 0) return this
            if (index == path.lastIndex) return removed(target, position)
            val member = target.members[position]
            if (member.value.isNull()) return this
            target = member.value as? ObjectSpan ?: throw noObject(path, index)
            index++
        }
    }

    private fun insert(target: ObjectSpan, multiline: Boolean, key: String, value: JsonElement): ConfigText {
        val members = target.members
        if (members.isEmpty()) {
            if (!multiline) return splice(target.start, target.end, "{${quoted(key)}: ${render(value, "", false)}}")
            val outer = lineIndent(target.start)
            val inner = outer + unit
            val member = inner + quoted(key) + ": " + render(value, inner, true)
            return splice(target.start, target.end, "{$newline$member$newline$outer}")
        }
        val last = members.last()
        val gap = if (members.size > 1) {
            text.substring(comma(members[members.size - 2]) + 1, last.keyStart)
        } else {
            text.substring(target.start + 1, last.keyStart).takeIf { '\n' in it } ?: " "
        }
        val colon = colon(last)
        val rendered = render(value, lineIndent(last.keyStart), multiline, colon)
        return splice(last.value.end, last.value.end, ",$gap${quoted(key)}$colon$rendered")
    }

    private fun replace(target: ObjectSpan, member: MemberSpan, value: JsonElement): ConfigText {
        val rendered = render(value, lineIndent(member.keyStart), '\n' in target.text(), colon(member))
        return splice(member.value.start, member.value.end, rendered)
    }

    private fun removed(target: ObjectSpan, position: Int): ConfigText {
        val members = target.members
        return when {
            members.size == 1 -> splice(target.start, target.end, "{}")
            position < members.lastIndex -> splice(members[position].keyStart, members[position + 1].keyStart, "")
            else -> splice(comma(members[position - 1]), members[position].value.end, "")
        }
    }

    private fun splice(start: Int, end: Int, replacement: String): ConfigText =
        ConfigText(text.substring(0, start) + replacement + text.substring(end))

    /** Whether [target] spans several lines, which an empty object takes from the object that holds it. */
    private fun multiline(target: ObjectSpan, parent: ObjectSpan?): Boolean = when {
        target.members.isNotEmpty() -> '\n' in target.text()
        parent == null -> true
        else -> '\n' in parent.text()
    }

    private fun render(value: JsonElement, indent: String, multiline: Boolean, colon: String = ": "): String {
        val inner = indent + unit
        return when (value) {
            is JsonObject -> when {
                value.isEmpty() -> "{}"

                !multiline -> value.entries.joinToString(", ", "{", "}") { (key, child) ->
                    quoted(key) + colon + render(child, indent, false, colon)
                }

                else -> value.entries.joinToString(",$newline", "{$newline", "$newline$indent}") { (key, child) ->
                    inner + quoted(key) + colon + render(child, inner, true, colon)
                }
            }

            is JsonArray -> when {
                value.isEmpty() -> "[]"

                !multiline || value.all { it is JsonPrimitive } ->
                    value.joinToString(", ", "[", "]") { render(it, indent, false, colon) }

                else -> value.joinToString(",$newline", "[$newline", "$newline$indent]") {
                    inner + render(it, inner, true, colon)
                }
            }

            is JsonPrimitive -> value.toString()
        }
    }

    /** What separates the key of [member] from its value, when that stays on one line. */
    private fun colon(member: MemberSpan): String =
        text.substring(member.keyEnd, member.value.start).takeIf { '\n' !in it } ?: ": "

    /** The index of the comma after [member]. */
    private fun comma(member: MemberSpan): Int = text.indexOf(',', member.value.end)

    private fun lineIndent(index: Int): String {
        val start = text.lastIndexOf('\n', index - 1) + 1
        return text.substring(start, index).takeWhile { it == ' ' || it == '\t' }
    }

    /** The indentation that the first object whose members start their own lines adds to its own. */
    private fun indentUnit(target: ObjectSpan): String? {
        val first = target.members.firstOrNull() ?: return null
        if ('\n' in text.substring(target.start, first.keyStart)) {
            val outer = lineIndent(target.start)
            val inner = lineIndent(first.keyStart)
            if (inner.length > outer.length && inner.startsWith(outer)) return inner.substring(outer.length)
        }
        return target.members.firstNotNullOfOrNull { (it.value as? ObjectSpan)?.let(::indentUnit) }
    }

    private fun Span.isNull(): Boolean = this is ScalarSpan && text() == "null"

    private fun Span.text(): String = text.substring(start, end)

    private fun noObject(path: List<String>, index: Int): ConfigPathException =
        ConfigPathException("'${path.take(index + 1).joinToString(".")}' holds no object.")

    private companion object {
        const val DEFAULT_INDENT = "  "

        fun quoted(key: String): String = JsonPrimitive(key).toString()
    }
}

private sealed class Span(val start: Int, val end: Int)

private class ObjectSpan(start: Int, end: Int, val members: List<MemberSpan>) : Span(start, end)

private class ArraySpan(start: Int, end: Int) : Span(start, end)

private class ScalarSpan(start: Int, end: Int) : Span(start, end)

private class MemberSpan(val keyStart: Int, val keyEnd: Int, val key: String, val value: Span)

/** Finds the spans of valid JSON. */
private class Parser(private val text: String) {
    private var index = if (text.startsWith('﻿')) 1 else 0

    fun root(): ObjectSpan = value() as? ObjectSpan ?: throw ConfigPathException("The config file holds no object.")

    private fun value(): Span {
        skipWhitespace()
        val start = index
        return when (text[index]) {
            '{' -> objectSpan(start)

            '[' -> {
                arrayEnd()
                ArraySpan(start, index)
            }

            '"' -> {
                index = stringEnd(index)
                ScalarSpan(start, index)
            }

            else -> {
                while (index < text.length && text[index] !in SCALAR_END) index++
                ScalarSpan(start, index)
            }
        }
    }

    private fun objectSpan(start: Int): ObjectSpan {
        val members = mutableListOf<MemberSpan>()
        index++
        skipWhitespace()
        if (text[index] == '}') return ObjectSpan(start, ++index, members)
        while (true) {
            skipWhitespace()
            val keyStart = index
            index = stringEnd(index)
            val key = Json.decodeFromString<String>(text.substring(keyStart, index))
            val keyEnd = index
            skipWhitespace()
            index++
            members += MemberSpan(keyStart, keyEnd, key, value())
            skipWhitespace()
            if (text[index++] == '}') return ObjectSpan(start, index, members)
        }
    }

    private fun arrayEnd() {
        index++
        skipWhitespace()
        if (text[index] == ']') {
            index++
            return
        }
        while (true) {
            value()
            skipWhitespace()
            if (text[index++] == ']') return
        }
    }

    private fun stringEnd(start: Int): Int {
        var end = start + 1
        while (text[end] != '"') end += if (text[end] == '\\') 2 else 1
        return end + 1
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in WHITESPACE) index++
    }

    private companion object {
        const val WHITESPACE = " \t\r\n"
        const val SCALAR_END = ",}] \t\r\n"
    }
}
