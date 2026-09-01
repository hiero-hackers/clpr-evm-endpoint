// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hiero.clpr.relay.e2e.LogStream;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One Besu container in a {@link BesuNetwork}: either the QBFT validator or a non-validator full
 * node.
 *
 * <p>Chain state and configuration live in <b>host</b> directories bind-mounted into the container.
 * Testcontainers destroys a container on {@code stop()}, so an in-container data directory would
 * mean {@link #restart()} silently resets the chain to genesis — which would make a node-restart
 * test pass for the wrong reason. Keeping the data directory on the host makes a restart a genuine
 * process restart against existing state.
 */
public final class BesuNode {

    /** JSON-RPC port inside the container. */
    public static final int RPC_PORT = 8545;

    /** P2P port inside the container. */
    public static final int P2P_PORT = 30303;

    private final String id;
    private final BesuNetworkSpec spec;
    private final TestAccount nodeKey;
    private final boolean validator;
    private final String staticIp;
    private final Network network;
    private final Path configDir;
    private final Path dataDir;
    private final int heapMb;

    private final LogStream logStream;

    @Nullable
    private GenericContainer<?> container;

    BesuNode(
            final String id,
            final BesuNetworkSpec spec,
            final TestAccount nodeKey,
            final boolean validator,
            final String staticIp,
            final Network network,
            final Path nodeDir,
            final int heapMb) {
        this.id = id;
        this.spec = spec;
        this.nodeKey = nodeKey;
        this.validator = validator;
        this.staticIp = staticIp;
        this.network = network;
        this.configDir = nodeDir.resolve("config");
        this.dataDir = nodeDir.resolve("data");
        this.heapMb = heapMb;
        this.logStream = new LogStream(id);
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(dataDir);
            // The Besu image runs as a non-root user, so the bind-mounted data directory has to be
            // writable by it. Widening the mode is the least fragile way to arrange that without
            // pinning the container to a uid.
            makeWorldWritable(configDir);
            makeWorldWritable(dataDir);
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to create host directories for Besu node " + id, e);
        }
    }

    /** Write genesis, node key and static-nodes before first start. */
    void writeConfiguration(final String genesisJson, final String staticNodesJson) {
        try {
            Files.writeString(configDir.resolve("genesis.json"), genesisJson, StandardCharsets.UTF_8);
            // Besu accepts the raw hex with or without a 0x prefix.
            Files.writeString(configDir.resolve("node-key"), nodeKey.privateKeyHex() + "\n", StandardCharsets.UTF_8);
            // Besu reads static-nodes.json from the DATA directory, not the config directory.
            // Peer discovery is disabled, so this file is the only way nodes find each other.
            Files.writeString(dataDir.resolve("static-nodes.json"), staticNodesJson, StandardCharsets.UTF_8);
            makeWorldWritable(configDir);
            makeWorldWritable(dataDir);
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to write Besu configuration for node " + id, e);
        }
    }

    /** @return the node id, which is also its network alias. */
    public String id() {
        return id;
    }

    /** @return {@code true} if this node is a QBFT validator. */
    public boolean isValidator() {
        return validator;
    }

    /** @return the node's pinned IP on the environment network. */
    public String ipAddress() {
        return staticIp;
    }

    /** @return the node's enode URL, computable without the container running. */
    public String enode() {
        return BesuQbftGenesis.enodeUrl(nodeKey, staticIp, P2P_PORT);
    }

    /** @return the funded account whose key is this node's identity. */
    public TestAccount nodeKey() {
        return nodeKey;
    }

    /** @return host-visible JSON-RPC URL (mapped port), for use from the test JVM. */
    public String jsonRpcUrl() {
        return "http://" + requireContainer().getHost() + ":"
                + requireContainer().getMappedPort(RPC_PORT);
    }

    /** @return in-network JSON-RPC URL, for use from other containers (e.g. the endpoints). */
    public String internalJsonRpcUrl() {
        return "http://" + id + ":" + RPC_PORT;
    }

    /** @return {@code true} if the container is up. */
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    /** Start the container (no-op if already running). */
    public void start() {
        if (isRunning()) {
            return;
        }
        final GenericContainer<?> c = new GenericContainer<>(spec.image())
                // Pin the address. Endpoint containers publish their peers' addresses on-chain and
                // ClprService only accepts IPv4 literals there, so the whole environment is built on
                // stable IPs rather than aliases. withIpv4Address applies to the network selected by
                // withNetwork below, which Testcontainers turns into the container's network mode
                // before create-command modifiers run.
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName(id).withIpv4Address(staticIp))
                .withNetwork(network)
                .withNetworkAliases(id)
                .withExposedPorts(RPC_PORT)
                .withFileSystemBind(configDir.toString(), "/config", BindMode.READ_ONLY)
                .withFileSystemBind(dataDir.toString(), "/data", BindMode.READ_WRITE)
                .withEnv("BESU_OPTS", "-Xmx" + heapMb + "m")
                .withCommand(commandArgs())
                // Capture output into a buffer that outlives the container. Testcontainers deletes
                // the container when a wait strategy fails, taking getLogs() with it — precisely
                // when the log is the only thing that explains the failure.
                .withLogConsumer(logStream.consumer())
                // Wait on the log line, not on an HTTP probe. Besu drops the connection on an empty
                // POST (and answers 415 to a GET), so the HTTP strategies that work for Anvil report
                // a socket error here and time out against a perfectly healthy node.
                .waitingFor(Wait.forLogMessage(".*JSON-RPC service started.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        spec.extraEnv().forEach(c::withEnv);
        c.start();
        this.container = c;
    }

    private String[] commandArgs() {
        final List<String> args = new ArrayList<>(List.of(
                "--genesis-file=/config/genesis.json",
                "--node-private-key-file=/config/node-key",
                "--data-path=/data",
                // FOREST keeps startup cheap for a throwaway chain; BONSAI's extra bookkeeping
                // buys nothing when the whole state lives for minutes.
                "--data-storage-format=FOREST",
                "--network-id=" + spec.chainId(),
                "--rpc-http-enabled",
                "--rpc-http-host=0.0.0.0",
                "--rpc-http-port=" + RPC_PORT,
                "--rpc-http-cors-origins=all",
                // DEBUG is required: the CLPR config proof needs eth_getProof plus raw header
                // access, and TXPOOL lets a load test see whether sends are queueing or landing.
                "--rpc-http-api=ETH,NET,WEB3,DEBUG,TXPOOL,MINER,QBFT",
                // Uncapped eth_call gas: reading contract state and previewing submitBundle both
                // exceed the default cap on a bundle of any size.
                "--rpc-gas-cap=0",
                "--host-allowlist=*",
                "--min-gas-price=" + spec.minGasPriceWei(),
                "--p2p-host=" + staticIp,
                "--p2p-port=" + P2P_PORT,
                // static-nodes.json is authoritative; discovery would only add noise on a
                // throwaway bridge network.
                "--discovery-enabled=false",
                "--logging=INFO"));
        return args.toArray(String[]::new);
    }

    /** Graceful stop. The host data directory survives, so {@link #start()} resumes the chain. */
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /** Stop and start again against the retained data directory. */
    public void restart() {
        stop();
        start();
    }

    /** SIGKILL — an abrupt crash, with no chance to flush state. */
    public void kill() {
        final GenericContainer<?> c = requireContainer();
        c.getDockerClient().killContainerCmd(c.getContainerId()).exec();
    }

    /**
     * Freeze the process. Unlike {@link #stop()} this keeps the port mapping and in-memory chain, so
     * callers see RPC timeouts rather than connection refusals — the shape of a real node outage.
     */
    public void pause() {
        final GenericContainer<?> c = requireContainer();
        c.getDockerClient().pauseContainerCmd(c.getContainerId()).exec();
    }

    /** Resume a paused container. */
    public void unpause() {
        final GenericContainer<?> c = requireContainer();
        c.getDockerClient().unpauseContainerCmd(c.getContainerId()).exec();
    }

    /** @return the captured output, which survives container removal and restarts. */
    public LogStream logs() {
        return logStream;
    }

    private GenericContainer<?> requireContainer() {
        final GenericContainer<?> c = container;
        if (c == null) {
            throw new IllegalStateException("Besu node " + id + " is not started");
        }
        return c;
    }

    private static void makeWorldWritable(final Path dir) throws IOException {
        final var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
        try {
            Files.setPosixFilePermissions(dir, perms);
        } catch (final UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; the bind mount will have to be permissive on its own.
        }
    }
}
