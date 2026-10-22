package me.kavishdevar.librepods.companion

import android.app.Application
import android.util.AtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class CompanionHoldStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val first = "00:11:22:33:44:55"
    private val second = "00:11:22:33:44:66"

    @Test fun aReleasedPairRemainsHeldAfterRecreationAndOnlyItsExplicitClearIsRemoved() {
        val file = File(files.root, "holds")
        CompanionHoldStore(AtomicFile(file)).save(setOf(first, second))
        val restarted = CompanionHoldStore(AtomicFile(file))
        assertEquals(setOf(first, second), restarted.load())
        restarted.save(restarted.load() - first)
        assertEquals(setOf(second), CompanionHoldStore(AtomicFile(file)).load())
    }

    @Test fun anAtomicBackupIsRecoveredInsteadOfLosingTheHeldPair() {
        val file = File(files.root, "holds")
        CompanionHoldStore(AtomicFile(file)).save(setOf(first))
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertEquals(setOf(first), CompanionHoldStore(AtomicFile(file)).load())
    }

    @Test fun unknownOrOversizedStorageCannotBecomeAnEmptyHoldSet() {
        val file = File(files.root, "holds")
        for (text in listOf("{}", "[null]", "[\"invalid\"]", "[\"$first\",\"$first\"]", " ".repeat(4097))) {
            file.writeText(text)
            assertThrows(Exception::class.java) { CompanionHoldStore(AtomicFile(file)).load() }
        }
    }

    @Test fun aFailedWriteKeepsThePreviousPersistedChoice() {
        val file = File(files.root, "holds")
        CompanionHoldStore(AtomicFile(file)).save(setOf(first))
        val failing = spy(AtomicFile(file))
        doThrow(IOException("Write unavailable")).`when`(failing).startWrite()
        assertThrows(IOException::class.java) { CompanionHoldStore(failing).save(emptySet()) }
        assertEquals(setOf(first), CompanionHoldStore(AtomicFile(file)).load())
    }
}

/** Android rename replaces the destination; the Windows JVM's File.renameTo does not. */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    companion object {
        @JvmStatic @Implementation
        fun rename(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
