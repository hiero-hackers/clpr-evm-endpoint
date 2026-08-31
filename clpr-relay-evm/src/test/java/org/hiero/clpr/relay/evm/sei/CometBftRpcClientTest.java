// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.sei;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CometBftRpcClientTest {

    @Test
    void getSignedHeader_decodesCometBftHexHeaderFields() throws Exception {
        final var lastBlockHash = "11".repeat(32);
        final var lastBlockPartHash = "22".repeat(32);
        final var lastCommitHash = "33".repeat(32);
        final var dataHash = "44".repeat(32);
        final var validatorsHash = "55".repeat(32);
        final var nextValidatorsHash = "66".repeat(32);
        final var consensusHash = "77".repeat(32);
        final var appHash = "88".repeat(32);
        final var lastResultsHash = "99".repeat(32);
        final var evidenceHash = "aa".repeat(32);
        final var proposerAddress = "bb".repeat(20);
        final var commitPartHash = "cc".repeat(32);
        final var signature = Bytes.fromHex("dd".repeat(64));
        final var signatureBase64 = Base64.getEncoder().encodeToString(signature.toByteArray());
        final var responseBody = """
                {"jsonrpc":"2.0","id":1,"result":{"signed_header":{"header":{
                  "version":{"block":"11","app":"0"},
                  "chain_id":"sei",
                  "height":"5",
                  "time":"2026-06-22T15:21:13.879944805Z",
                  "last_block_id":{"hash":"%s","parts":{"total":1,"hash":"%s"}},
                  "last_commit_hash":"%s",
                  "data_hash":"%s",
                  "validators_hash":"%s",
                  "next_validators_hash":"%s",
                  "consensus_hash":"%s",
                  "app_hash":"%s",
                  "last_results_hash":"%s",
                  "evidence_hash":"%s",
                  "proposer_address":"%s"
                },"commit":{
                  "round":0,
                  "block_id":{"parts":{"total":1,"hash":"%s"}},
                  "signatures":[{"block_id_flag":2,"timestamp":"2026-06-22T15:21:13.826870805Z","signature":"%s"}]
                }}}}
                """.formatted(
                        lastBlockHash,
                        lastBlockPartHash,
                        lastCommitHash,
                        dataHash,
                        validatorsHash,
                        nextValidatorsHash,
                        consensusHash,
                        appHash,
                        lastResultsHash,
                        evidenceHash,
                        proposerAddress,
                        commitPartHash,
                        signatureBase64);

        try (final var server = new OneShotJsonServer(responseBody)) {
            final var client = new CometBftRpcClient(server.url(), 0, Duration.ofSeconds(2));

            final var signedHeader = client.getSignedHeader(5);
            final var header = signedHeader.header();
            final var commit = signedHeader.commit();

            assertThat(header.validatorsHash()).isEqualTo(Bytes.fromHex(validatorsHash));
            assertThat(header.nextValidatorsHash()).isEqualTo(Bytes.fromHex(nextValidatorsHash));
            assertThat(header.appHash()).isEqualTo(Bytes.fromHex(appHash));
            assertThat(header.lastBlockId().hash()).isEqualTo(Bytes.fromHex(lastBlockHash));
            assertThat(header.lastBlockId().partSetHash()).isEqualTo(Bytes.fromHex(lastBlockPartHash));
            assertThat(header.lastCommitHash()).isEqualTo(Bytes.fromHex(lastCommitHash));
            assertThat(header.dataHash()).isEqualTo(Bytes.fromHex(dataHash));
            assertThat(header.consensusHash()).isEqualTo(Bytes.fromHex(consensusHash));
            assertThat(header.lastResultsHash()).isEqualTo(Bytes.fromHex(lastResultsHash));
            assertThat(header.evidenceHash()).isEqualTo(Bytes.fromHex(evidenceHash));
            assertThat(header.proposerAddress()).isEqualTo(Bytes.fromHex(proposerAddress));
            assertThat(commit.partSetHash()).isEqualTo(Bytes.fromHex(commitPartHash));
            assertThat(commit.signersBits()).isEqualTo(Bytes.fromHex("80"));
            assertThat(commit.signatures()).singleElement().satisfies(sig -> assertThat(sig.signature())
                    .isEqualTo(signature));
        }
    }

    private static final class OneShotJsonServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor;

        private OneShotJsonServer(final String responseBody) throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> serve(responseBody));
        }

        private String url() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
        }

        private void serve(final String responseBody) {
            try (final var socket = serverSocket.accept();
                    final var reader =
                            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    final var output = socket.getOutputStream()) {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Drain request headers before writing the response.
                }
                final var responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                final var headers = ("HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: "
                                + responseBytes.length
                                + "\r\n"
                                + "Channel: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
                output.write(headers);
                output.write(responseBytes);
                output.flush();
            } catch (final IOException ignored) {
                // Test teardown can close the server socket before a request arrives.
            }
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
