// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

/** Thrown when an EVM JSON-RPC response contains an {@code error} field. */
public class JsonRpcException extends RuntimeException {
    private final int code;

    /**
     * Create a new exception from a JSON-RPC error object.
     *
     * @param code the JSON-RPC error code
     * @param message the JSON-RPC error message
     */
    public JsonRpcException(final int code, final String message) {
        super("JSON-RPC error " + code + ": " + message);
        this.code = code;
    }

    /**
     * Return the JSON-RPC error code.
     *
     * @return the error code
     */
    public int code() {
        return code;
    }
}
