// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;

/**
 * The framework-managed pool of prefunded test accounts.
 *
 * <p>Keys are derived deterministically from a fixed seed, which buys two things. Every account is
 * known <em>before</em> any chain starts, so the whole pool can be written into the genesis
 * {@code alloc} in one shot and no test ever has to mine a funding transaction during setup. And a
 * failing run is reproducible: account 7 is the same account on every machine and in every rerun,
 * so a diagnostics dump naming it is actionable.
 *
 * <p>Allocation is by {@link #allocate(String)} rather than by index so that the endpoint signers,
 * the contract deployer, and the load generator's senders cannot accidentally share an account.
 * Sharing one would be silent and destructive: two independent nonce sequences on one address
 * produce {@code nonce too low} rejections that look exactly like the relay bug the suite is
 * supposed to detect.
 */
public final class Accounts {

    /**
     * Seed for key derivation. Fixed on purpose — see the class comment. Changing it invalidates
     * nothing but does renumber every account, so leave it alone unless a collision with a
     * well-known dev key needs breaking.
     */
    private static final String SEED = "clpr-evm-endpoint/e2e/v1";

    /** secp256k1 group order; a derived scalar must be in {@code [1, n)}. */
    private static final BigInteger CURVE_ORDER =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private final List<TestAccount> pool;
    private int nextFree;

    /**
     * Derive a pool of {@code size} accounts.
     *
     * @param size number of accounts to derive; also the number of genesis alloc entries
     */
    public Accounts(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("account pool size must be positive, got " + size);
        }
        final List<TestAccount> derived = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            derived.add(derive(i, "unallocated"));
        }
        this.pool = derived;
    }

    /**
     * Claim the next unused account.
     *
     * @param purpose label for diagnostics, e.g. {@code "endpoint-0 signer"}
     * @return the claimed account
     * @throws IllegalStateException if the pool is exhausted
     */
    public synchronized TestAccount allocate(final String purpose) {
        if (nextFree >= pool.size()) {
            throw new IllegalStateException("test account pool exhausted after " + pool.size()
                    + " accounts; raise E2EEnvironmentImpl.ACCOUNT_POOL_SIZE. The pool is environment-wide"
                    + " and shared by every chain, so validators, deployers, endpoint signers, channel"
                    + " operators and load senders all draw from it.");
        }
        final int index = nextFree++;
        final TestAccount labelled = derive(index, purpose);
        pool.set(index, labelled);
        return labelled;
    }

    /**
     * Claim {@code count} accounts at once.
     *
     * @param count how many to claim
     * @param purpose label applied to all of them, suffixed with the per-account ordinal
     * @return the claimed accounts, in allocation order
     */
    public synchronized List<TestAccount> allocate(final int count, final String purpose) {
        final List<TestAccount> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(allocate(purpose + "#" + i));
        }
        return out;
    }

    /**
     * Every account in the pool, allocated or not. This is what the genesis builder writes into
     * {@code alloc} — the whole pool is funded up front because which accounts a test will claim is
     * not known until the test body runs, and by then the chain is already up.
     *
     * @return all accounts, in index order
     */
    public synchronized List<TestAccount> all() {
        return List.copyOf(pool);
    }

    /** @return how many accounts have been claimed so far. */
    public synchronized int allocatedCount() {
        return nextFree;
    }

    /**
     * Derive account {@code index} as {@code keccak256(SEED || index)}, rehashing on the
     * (cryptographically negligible) chance the scalar falls outside {@code [1, n)}.
     */
    private static TestAccount derive(final int index, final String purpose) {
        byte[] candidate = AbiCodec.Keccak256.keccak256((SEED + "/" + index).getBytes(StandardCharsets.UTF_8));
        BigInteger scalar = new BigInteger(1, candidate);
        while (scalar.signum() == 0 || scalar.compareTo(CURVE_ORDER) >= 0) {
            candidate = AbiCodec.Keccak256.keccak256(candidate);
            scalar = new BigInteger(1, candidate);
        }
        final String privateKeyHex = AbiCodec.toHex(candidate);
        final EthSigner signer = new EthSigner(privateKeyHex);
        return new TestAccount(
                index, privateKeyHex, signer.address(), EthSigner.derivePublicKey(privateKeyHex), purpose);
    }
}
