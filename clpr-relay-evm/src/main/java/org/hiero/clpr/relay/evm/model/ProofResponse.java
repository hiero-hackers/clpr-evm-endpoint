// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.model;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;

public record ProofResponse(List<Bytes> accountProof, List<StorageProofEntry> storageProof) {
    public record StorageProofEntry(Bytes key, Bytes value, List<Bytes> proof) {}
}
