package org.foedusprogramme.alexandrite.sdk.transcript

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** The mechanism [TranscriptCodec] relies on, shown on a hierarchy of its own. */
class UnknownSubtypeTest {
    @SubclassOptInRequired(InternalAlexandriteApi::class)
    interface Shape

    @Serializable
    @SerialName("circle")
    data class Circle(val radius: Int) : Shape

    @Serializable
    data class Drawing(val shapes: List<Shape>)

    data class UnknownShape(val type: String, val json: JsonObject) : Shape

    private class UnknownShapeSerializer(type: String) : KSerializer<Shape> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor(type)

        override fun serialize(encoder: Encoder, value: Shape) {
            (encoder as JsonEncoder).encodeJsonElement(JsonObject((value as UnknownShape).json - "type"))
        }

        override fun deserialize(decoder: Decoder): Shape {
            val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
            return UnknownShape(descriptor.serialName, json)
        }
    }

    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(Shape::class) {
                subclass(Circle::class)
                defaultDeserializer { type -> type?.let(::UnknownShapeSerializer) }
            }
            polymorphicDefaultSerializer(Shape::class) { value ->
                if (value is UnknownShape) UnknownShapeSerializer(value.type) else null
            }
        }
    }

    @Test
    fun `an unknown subtype of an opt-in-protected interface decodes to its fallback with the raw JSON`() {
        val text = """{"shapes":[{"type":"circle","radius":2},{"type":"star","points":5,"inner":{"a":[1]}}]}"""

        val drawing = json.decodeFromString<Drawing>(text)

        val star = json.parseToJsonElement("""{"type":"star","points":5,"inner":{"a":[1]}}""").jsonObject
        assertEquals(Drawing(listOf(Circle(2), UnknownShape("star", star))), drawing)
        assertEquals(text, json.encodeToString(drawing))
    }
}
