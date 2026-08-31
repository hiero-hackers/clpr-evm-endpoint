// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.jspecify.annotations.NonNull;

/**
 * A {@link TransactionSubmitter} decorator that re-encodes each verified bundle into the stub proof
 * format ({@code "CLPRSTUB" || protobuf(ClprBundleContent)}) before delegating the on-chain
 * submission.
 *
 * <p><b>Why this exists.</b> {@code submitBundle} forwards the proof bytes verbatim to the
 * channel's on-chain verifier contract. The integration tests deploy {@code StubClprVerifier},
 * which only accepts the {@code CLPRSTUB} format — so an endpoint that receives a real EVM
 * proof over the wire cannot submit it as-is. Because the inbound {@code QbftProofParser} already
 * parses the bundle into a {@link ParsedBundle} (queue metadata + messages), this decorator can
 * rebuild the {@code CLPRSTUB} proof the stub contract expects from that parsed content. The result:
 * the relay keeps constructing and parsing <i>real</i> EVM proofs on the wire, while still
 * submitting successfully against the stub verifier.
 *
 * <p><b>Temporary.</b> This is a stop-gap for integration testing until a real on-chain EVM
 * verifier contract exists. It is injected only by the integration-test wiring
 * ({@code RelayTestSupport.buildRelay}); production builds wrap the EVM submitter with the identity
 * decorator and submit the raw proof to a real verifier — re-encoding to the (unverified) stub
 * format there would bypass the cryptographic check.
 */
public final class StubReencodingTransactionSubmitter implements TransactionSubmitter {

    private final TransactionSubmitter delegate;

    /**
     * @param delegate the underlying submitter that performs the actual on-chain transaction
     */
    public StubReencodingTransactionSubmitter(@NonNull final TransactionSubmitter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void submitBundle(final ClprChannel channel, final ParsedBundle verified) {
        delegate.submitBundle(channel, reencode(verified));
    }

    /** Rebuild the {@code CLPRSTUB}-prefixed proof from the parsed content. */
    private static ParsedBundle reencode(final ParsedBundle verified) {
        final ClprBundleContent content = ClprBundleContent.newBuilder()
                .metadata(verified.metadata())
                .messages(verified.messages())
                .build();
        final byte[] prefix = StubBundleConstructor.MAGIC_PREFIX.toByteArray();
        final byte[] proto = ClprBundleContent.PROTOBUF.toBytes(content).toByteArray();
        final byte[] stubProof = new byte[prefix.length + proto.length];
        System.arraycopy(prefix, 0, stubProof, 0, prefix.length);
        System.arraycopy(proto, 0, stubProof, prefix.length, proto.length);
        return new ParsedBundle(verified.metadata(), verified.messages(), Bytes.wrap(stubProof));
    }
}
