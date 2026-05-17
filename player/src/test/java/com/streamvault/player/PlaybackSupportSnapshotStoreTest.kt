package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Method
import org.junit.Test

/**
 * Compile-time and reflection-level checks on [PlaybackSupportSnapshotStore].
 *
 * Off-main execution is guaranteed by `withContext(Dispatchers.IO)` plus the
 * `check(Looper)` runtime guard introduced in M7 T5. No instrumented StrictMode
 * test is added — see SCOPE policy L2 (no Android instrumented tests for
 * thread-policy enforcement at unit-test layer).
 *
 * The store's primary constructor requires an Android [android.content.Context]
 * to resolve `filesDir`. Mocking that without Robolectric/MockK is out of scope
 * for this milestone (would force a new test dependency on the `:player`
 * module), so this suite focuses on the structural contract of the suspend
 * write API. The Java-reflection introspection below is sufficient: a Kotlin
 * `suspend fun` is compiled to a JVM method whose last parameter is a
 * [kotlin.coroutines.Continuation], so we can verify the suspend nature
 * without depending on `kotlin-reflect`.
 *
 * Disk-write behaviour is exercised on device through the engine collector
 * loop in `Media3PlayerEngine.startEngineCollectors` and via the existing
 * baseline benchmark harness (M7 T4).
 */
class PlaybackSupportSnapshotStoreTest {

    @Test
    fun `write is compiled as a suspend function`() {
        val writeMethod: Method = PlaybackSupportSnapshotStore::class.java.declaredMethods
            .single { it.name == "write" }

        // A Kotlin `suspend fun foo(payload: String)` is lowered to a JVM
        // method `write(String, Continuation)`. The trailing Continuation
        // parameter is the canonical marker of a suspend function.
        val params = writeMethod.parameterTypes
        assertThat(params).hasLength(2)
        assertThat(params[0]).isEqualTo(String::class.java)
        assertThat(params[1].name).isEqualTo("kotlin.coroutines.Continuation")
    }

    @Test
    fun `write returns Object to allow COROUTINE_SUSPENDED sentinel`() {
        val writeMethod: Method = PlaybackSupportSnapshotStore::class.java.declaredMethods
            .single { it.name == "write" }

        // Kotlin suspend functions returning Unit are lowered to JVM methods
        // returning Object — they may return either Unit or the
        // COROUTINE_SUSPENDED sentinel at runtime.
        assertThat(writeMethod.returnType).isEqualTo(Any::class.java)
    }
}
