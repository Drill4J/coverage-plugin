/**
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.plugins.test2code.util

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*


class StringInternDeserializationStrategy<T>(private val deserializationStrategy: DeserializationStrategy<T>) :
    DeserializationStrategy<T> by deserializationStrategy {
    override fun deserialize(decoder: Decoder): T {
        return deserializationStrategy.deserialize(DecodeAdapter(decoder))
    }
}

internal class StringInternDecoder(private val decoder: CompositeDecoder) : CompositeDecoder by decoder {
    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        return decoder.decodeStringElement(descriptor, index).run {
            takeIf { descriptor.getElementAnnotations(index).any { it is StringIntern } }?.intern() ?: this
        }
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        return decoder.decodeSerializableElement(
            descriptor,
            index,
            StringInternDeserializationStrategy(deserializer),
            previousValue
        )
    }
}

internal class DecodeAdapter(private val decoder: Decoder) : Decoder by decoder {
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return StringInternDecoder(decoder.beginStructure(descriptor))
    }
}
