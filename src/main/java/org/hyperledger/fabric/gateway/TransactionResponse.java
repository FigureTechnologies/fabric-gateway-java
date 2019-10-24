package org.hyperledger.fabric.gateway;

import java.io.Serializable;
import java.util.UUID;

public class TransactionResponse implements Serializable {

    private final UUID correlationId;
    private final String transactionId;
    private final byte[] payload;

    public TransactionResponse(UUID correlationId, String transactionId, byte[] payload) {
        this.correlationId = correlationId;
        this.transactionId = transactionId;
        this.payload = payload;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public byte[] getPayload() {
        return payload;
    }
}
