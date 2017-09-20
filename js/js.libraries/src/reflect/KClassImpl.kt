/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package kotlin.reflect.js.internal

import kotlin.reflect.*

internal abstract class KClassImpl<T : Any>(
        internal val jClass: JsClass<T>,
        private val givenSimpleName: String? = null
) : KClass<T> {
    // TODO: use FQN
    private val hashCodeValue = simpleName?.hashCode() ?: 0

    override val annotations: List<Annotation>
        get() = TODO()
    override val constructors: Collection<KFunction<T>>
        get() = TODO()
    override val isAbstract: Boolean
        get() = TODO()
    override val isCompanion: Boolean
        get() = TODO()
    override val isData: Boolean
        get() = TODO()
    override val isFinal: Boolean
        get() = TODO()
    override val isInner: Boolean
        get() = TODO()
    override val isOpen: Boolean
        get() = TODO()
    override val isSealed: Boolean
        get() = TODO()
    override val members: Collection<KCallable<*>>
        get() = TODO()
    override val nestedClasses: Collection<KClass<*>>
        get() = TODO()
    override val objectInstance: T?
        get() = TODO()
    override val qualifiedName: String?
        get() = TODO()
    override val supertypes: List<KType>
        get() = TODO()
    override val typeParameters: List<KTypeParameter>
        get() = TODO()
    override val visibility: KVisibility?
        get() = TODO()

    override fun equals(other: Any?): Boolean {
        return other is KClassImpl<*> && jClass == other.jClass
    }

    override fun hashCode(): Int {
        return hashCodeValue
    }

    override fun toString(): String {
        // TODO: use FQN
        return "class $simpleName"
    }
}

internal class SimpleKClassImpl<T : Any>(jClass: JsClass<T>) : KClassImpl<T>(jClass) {
    private val metadata = jClass.asDynamic().`$metadata$`

    override val simpleName: String? = metadata?.simpleName

    override fun isInstance(value: Any?): Boolean {
        return js("Kotlin").isType(value, jClass)
    }
}

internal class PrimitiveKClassImpl<T : Any>(
        jClass: JsClass<T>,
        private val givenSimpleName: String,
        private val isInstanceFunction: (Any?) -> Boolean
) : KClassImpl<T>(jClass) {
    override fun equals(other: Any?): Boolean {
        if (other !is PrimitiveKClassImpl<*>) return false
        return super.equals(other) && givenSimpleName == other.givenSimpleName
    }

    override val simpleName: String? = givenSimpleName

    override fun isInstance(value: Any?): Boolean {
        return isInstanceFunction(value)
    }
}