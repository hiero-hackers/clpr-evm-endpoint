// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.testfixtures;

import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.jspecify.annotations.NonNull;

public class PassThroughCodec implements BundlePayloadCodec {
    private boolean parseCalled = false;

    @NonNull
    @Override
    public ParsedBundle decodeBundle(@NonNull Bytes p) {
        parseCalled = true;
        var meta = ClprQueueMetadata.newBuilder().nextMessageId(1L).build();
        return new ParsedBundle(meta, List.of(), p);
    }

    @NonNull
    @Override
    public ParsedBundle parseBundle(@NonNull Bytes p, long receivedMessageId) {
        return decodeBundle(p);
    }

    public boolean isParseCalled() {
        return parseCalled;
    }
}
