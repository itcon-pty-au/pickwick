package io.pickwick.app

import io.pickwick.app.data.LanServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

class IndexSourceParamTest {

    private fun target(id: String) = "/index?source=" + URLEncoder.encode(id, "UTF-8")

    @Test fun plainChannelIdRoundTrips() {
        assertEquals("UCHnj59g7jezwTy5GeL8EA_g", LanServer.indexSourceParam(target("UCHnj59g7jezwTy5GeL8EA_g")))
    }

    @Test fun handleAndLegacyIdsRoundTrip() {
        for (id in listOf("@natgeokids", "user/scishowkids", "c/microcosmos", "@BBC.Earth")) {
            assertEquals(id, LanServer.indexSourceParam(target(id)))
        }
    }

    @Test fun unencodedLegacyClientStillParses() {
        assertEquals("UCabc-_1", LanServer.indexSourceParam("/index?source=UCabc-_1"))
    }

    @Test fun rejectsTraversalAndJunk() {
        assertNull(LanServer.indexSourceParam(target("../../shared_prefs/x")))
        assertNull(LanServer.indexSourceParam(target("a b")))
        assertNull(LanServer.indexSourceParam(target("x".repeat(201))))
        assertNull(LanServer.indexSourceParam("/index?source=%zz"))
        assertNull(LanServer.indexSourceParam("/index"))
    }
}
