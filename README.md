# CLPR EVM Relay

A standalone Java 25 CLPR endpoint for EVM-compatible blockchains. Communicates with peer endpoints via gRPC, reads on-chain CLPR Service contract state via standard JSON-RPC, and submits `submitBundle()` transactions.

## Modules

|          Module          |                 Description                  |
|--------------------------|----------------------------------------------|
| `clpr-relay-proto`       | Protobuf definitions and PBJ-generated types |
| `clpr-relay-core`        | Core interfaces and domain types             |
| `clpr-relay-grpc-server` | gRPC server (Helidon + PBJ)                  |
| `clpr-relay-grpc-client` | gRPC outbound peer client                    |
| `clpr-relay-evm`         | EVM chain interaction (JSON-RPC)             |
| `clpr-relay-sync`        | Sync orchestration and peer management       |
| `clpr-relay-app`         | Application entry point and configuration    |

## Building

Requires JDK 25.

```bash
./gradlew build
```

## Running

```bash
java -Drelay.grpc.port=9545 \
     -Drelay.evm.jsonRpcUrl=http://localhost:8545 \
     -Drelay.evm.contractAddress=0x... \
     -jar clpr-relay-app/build/libs/clpr-relay-app.jar
```

See `clpr-relay-app/src/main/resources/relay-config-example.yaml` for configuration options.

## License

Apache License 2.0
