package io.pickwick.app

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import io.pickwick.app.data.SmbCatalog
import io.pickwick.app.data.SmbConnection
import io.pickwick.app.data.SmbLibrary
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in read-only checks. Set host/share/path env vars to a dedicated test fixture. */
class SmbNetworkIntegrationTest {
    @Test fun `server negotiates SMB2 or SMB3`() {
        val host = System.getenv("PICKWICK_SMB_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        SMBClient(SmbConfig.builder().withTimeout(5, TimeUnit.SECONDS).withSoTimeout(5, TimeUnit.SECONDS).build()).use { client ->
            client.connect(host, System.getenv("PICKWICK_SMB_TEST_PORT")?.toInt() ?: 445).use { connection ->
                val dialect = connection.negotiatedProtocol.dialect.name
                println("Negotiated protocol: $dialect")
                assertTrue(dialect.startsWith("SMB_2") || dialect.startsWith("SMB_3"))
            }
        }
    }

    @Test fun `share supports listing and random access reads`() {
        val host = System.getenv("PICKWICK_SMB_TEST_HOST")
        val share = System.getenv("PICKWICK_SMB_TEST_SHARE")
        val path = System.getenv("PICKWICK_SMB_TEST_FILE")
        assumeTrue(!host.isNullOrBlank() && !share.isNullOrBlank() && !path.isNullOrBlank())
        val user = System.getenv("PICKWICK_SMB_TEST_USER").orEmpty()
        val c = SmbCatalog(name = "Test", host = host!!, share = share!!, username = user,
            password = System.getenv("PICKWICK_SMB_TEST_PASSWORD").orEmpty(), guest = user.isEmpty(),
            port = System.getenv("PICKWICK_SMB_TEST_PORT")?.toInt() ?: 445)
        SmbConnection(c).use { smb ->
            assertTrue(smb.share.list("").isNotEmpty())
            smb.open(path!!).use { file ->
                val size = file.fileInformation.standardInformation.endOfFile
                assertTrue(size > 64)
                val first = ByteArray(32)
                assertEquals(32, file.read(first, 0, 0, 32))
                assertEquals(32, file.read(ByteArray(32), size - 32, 0, 32))
                val again = ByteArray(32)
                assertEquals(32, file.read(again, 0, 0, 32))
                assertArrayEquals(first, again)
            }
        }
    }
}
