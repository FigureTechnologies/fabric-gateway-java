/*
 * Copyright 2019 IBM All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.gateway.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.hyperledger.fabric.gateway.Contract;
import org.hyperledger.fabric.gateway.ContractEvent;
import org.hyperledger.fabric.gateway.ContractException;
import org.hyperledger.fabric.gateway.Transaction;
import org.hyperledger.fabric.gateway.TransactionResponse;
import org.hyperledger.fabric.gateway.impl.event.BlockListenerSession;
import org.hyperledger.fabric.gateway.impl.event.ListenerSession;
import org.hyperledger.fabric.gateway.impl.event.Listeners;
import org.hyperledger.fabric.gateway.impl.event.ReplayListenerSession;
import org.hyperledger.fabric.gateway.spi.Checkpointer;
import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.User;

public final class ContractImpl implements Contract, AutoCloseable {
    private final NetworkImpl network;
    private final String chaincodeId;
    private final String name;
    private final Map<Consumer<ContractEvent>, ListenerSession> contractListenerSessions = new HashMap<>();

    ContractImpl(NetworkImpl network, String chaincodeId, String name) {
        this.network = network;
        this.chaincodeId = chaincodeId;
        this.name = name;
    }

    @Override
    public Transaction createTransaction(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Transaction must be a non-empty string");
        }
        String qualifiedName = getQualifiedName(name);
        return new TransactionImpl(this, qualifiedName);
    }

    @Override
    public TransactionResponse submitTransaction(UUID correlationId, String name, String... args) throws ContractException, TimeoutException, InterruptedException {
        return createTransaction(name).submit(correlationId, args);
    }

    @Override
    public TransactionResponse evaluateTransaction(UUID correlationId, String name, String... args) throws ContractException {
        return createTransaction(name).evaluate(correlationId, args);
    }

    @Override
    public TransactionResponse submitTransaction(UUID correlationId, String name, User userContext, String... args) throws ContractException, TimeoutException, InterruptedException {
        return createTransaction(name).submit(correlationId, userContext, args);
    }

    @Override
    public TransactionResponse evaluateTransaction(UUID correlationId, String name, User userContext, String... args) throws ContractException {
        return createTransaction(name).evaluate(correlationId, userContext, args);
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Consumer<ContractEvent> listener) {
        synchronized (contractListenerSessions) {
            contractListenerSessions.computeIfAbsent(listener, k -> {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId);
                return new BlockListenerSession(network.getBlockSource(), Listeners.fromContract(contractListener));
            });
        }
        return listener;
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Consumer<ContractEvent> listener, String eventName) {
        return addContractListener(listener, getEventNamePattern(eventName));
    }

    private Pattern getEventNamePattern(String eventName) {
        return Pattern.compile(Pattern.quote(eventName));
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Consumer<ContractEvent> listener, Pattern eventNamePattern) {
        synchronized(contractListenerSessions) {
            contractListenerSessions.computeIfAbsent(listener, k -> {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId, eventNamePattern);
                return new BlockListenerSession(network.getBlockSource(), Listeners.fromContract(contractListener));
            });
        }
        return listener;
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Checkpointer checkpointer, Consumer<ContractEvent> listener) throws IOException {
        synchronized (contractListenerSessions) {
            if (!contractListenerSessions.containsKey(listener)) {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId);
                ListenerSession session = newCheckpointListenerSession(checkpointer, contractListener);
                contractListenerSessions.put(listener, session);
            }
        }
        return listener;
    }

    private ListenerSession newCheckpointListenerSession(Checkpointer checkpointer, Consumer<ContractEvent> contractListener) throws IOException {
        Consumer<BlockEvent> checkpointListener = Listeners.checkpointContract(checkpointer, contractListener);
        return network.newCheckpointListenerSession(checkpointer, checkpointListener);
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Checkpointer checkpointer, Consumer<ContractEvent> listener, String eventName) throws IOException {
        return addContractListener(checkpointer, listener, getEventNamePattern(eventName));
    }

    @Override
    public Consumer<ContractEvent> addContractListener(Checkpointer checkpointer, Consumer<ContractEvent> listener, Pattern eventNamePattern) throws IOException {
        synchronized (contractListenerSessions) {
            if (!contractListenerSessions.containsKey(listener)) {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId, eventNamePattern);
                ListenerSession session = newCheckpointListenerSession(checkpointer, contractListener);
                contractListenerSessions.put(listener, session);
            }
        }
        return listener;
    }

    @Override
    public Consumer<ContractEvent> addContractListener(long startBlock, Consumer<ContractEvent> listener) {
        synchronized (contractListenerSessions) {
            if (!contractListenerSessions.containsKey(listener)) {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId);
                ListenerSession session = newReplayListenerSession(startBlock, contractListener);
                contractListenerSessions.put(listener, session);
            }
        }
        return listener;
    }

    private ListenerSession newReplayListenerSession(long startBlock, Consumer<ContractEvent> contractListener) {
        Consumer<BlockEvent> blockListener = Listeners.fromContract(contractListener);
        return new ReplayListenerSession(network, blockListener, startBlock);
    }

    @Override
    public Consumer<ContractEvent> addContractListener(long startBlock, Consumer<ContractEvent> listener, String eventName) {
        return addContractListener(startBlock, listener, getEventNamePattern(eventName));
    }

    @Override
    public Consumer<ContractEvent> addContractListener(long startBlock, Consumer<ContractEvent> listener, Pattern eventNamePattern) {
        synchronized (contractListenerSessions) {
            if (!contractListenerSessions.containsKey(listener)) {
                Consumer<ContractEvent> contractListener = Listeners.contract(listener, chaincodeId, eventNamePattern);
                ListenerSession session = newReplayListenerSession(startBlock, contractListener);
                contractListenerSessions.put(listener, session);
            }
        }
        return listener;
    }

    @Override
    public void removeContractListener(Consumer<ContractEvent> listener) {
        ListenerSession session;
        synchronized (contractListenerSessions) {
            session = contractListenerSessions.remove(listener);
        }
        if (session != null) {
            session.close();
        }
    }

    public NetworkImpl getNetwork() {
        return network;
    }

    public String getChaincodeId() {
        return chaincodeId;
    }

    private String getQualifiedName(String tname) {
        return this.name.isEmpty() ? tname : this.name + ':' + tname;
    }

    @Override
    public void close() {
        synchronized (contractListenerSessions) {
            contractListenerSessions.values().forEach(ListenerSession::close);
            contractListenerSessions.clear();
        }
    }

    @Override
    public String toString() {
        return GatewayUtils.toString(this,
                "name=" + (name.isEmpty() ? chaincodeId : chaincodeId + ':' + name),
                "contractListenerSessions=" + contractListenerSessions);
    }
}
