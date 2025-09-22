package com.hisa.nostr

import com.hisa.data.nostr.EventVerifier
import org.junit.Test

class EventVerifierTest {
    @Test
    fun verifySampleRejectedEvent() {
        val json = """
{"id":"c50e6dce79d6c2415a2e331cbf208e21ebd703c922d25bac9cfd49909f755d1b","pubkey":"6a495d7df64516e7ff64ce0774502f410251e21c3fcb8e73023917f49bbee066","created_at":1703430137,"kind":1059,"tags":[["p","265090a95d6ccb8b3063e8dbc55dc7ab4878a50b974eae92e334097283a893cc"],["x","fa6ed6213e10d19f77b8c76d5eed9a874993178a648ab5165f35eade0ed1994b"]],"content":"<truncated>","sig":"6ae063c54e0294cf0cdf1a7f2e444479806e541e39f7cbfa6744b04eebbe81bb0c397ee9355cfafad0a407029a7e566f30ee3b0e2a4bd04e38e3a141c915d553"}
""".trimIndent()

        val r = EventVerifier.verifyEvent(json)
        println("idMatches=${r.idMatches} signatureValid=${r.signatureValid} computedId=${r.computedId} reason=${r.reason}")
        assert(!r.signatureValid)
    }
}
