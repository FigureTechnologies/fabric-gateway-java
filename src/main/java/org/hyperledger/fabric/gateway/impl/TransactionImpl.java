/*
 * Copyright 2019 IBM All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.gateway.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.gateway.ContractException;
import org.hyperledger.fabric.gateway.GatewayRuntimeException;
import org.hyperledger.fabric.gateway.Transaction;
import org.hyperledger.fabric.gateway.TransactionResponse;
import org.hyperledger.fabric.gateway.spi.CommitHandler;
import org.hyperledger.fabric.gateway.spi.CommitHandlerFactory;
import org.hyperledger.fabric.gateway.spi.Query;
import org.hyperledger.fabric.gateway.spi.QueryHandler;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.ServiceDiscovery;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.TransactionRequest;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.ServiceDiscoveryException;

import javax.annotation.Nullable;

import static org.hyperledger.fabric.sdk.Channel.DiscoveryOptions.createDiscoveryOptions;

public final class TransactionImpl implements Transaction {
    private static final Log LOG = LogFactory.getLog(TransactionImpl.class);

    private static final long DEFAULT_ORDERER_TIMEOUT = 60;
    private static final TimeUnit DEFAULT_ORDERER_TIMEOUT_UNIT = TimeUnit.SECONDS;

    private final ContractImpl contract;
    private final String name;
    private final NetworkImpl network;
    private final Channel channel;
    private final GatewayImpl gateway;
    private final CommitHandlerFactory commitHandlerFactory;
    private TimePeriod commitTimeout;
    private final QueryHandler queryHandler;
    private Map<String, byte[]> transientData = null;
    private Collection<Peer> endorsingPeers = null;

    TransactionImpl(final ContractImpl contract, final String name) {
        this.contract = contract;
        this.name = name;
        network = contract.getNetwork();
        channel = network.getChannel();
        gateway = network.getGateway();
        commitHandlerFactory = gateway.getCommitHandlerFactory();
        commitTimeout = gateway.getCommitTimeout();
        queryHandler = network.getQueryHandler();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Transaction setTransient(final Map<String, byte[]> transientData) {
        this.transientData = transientData;
        return this;
    }

    @Override
    public Transaction setCommitTimeout(final long timeout, final TimeUnit timeUnit) {
        commitTimeout = new TimePeriod(timeout, timeUnit);
        return this;
    }

    @Override
    public Transaction setEndorsingPeers(final Collection<Peer> peers) {
        endorsingPeers = peers;
        return this;
    }

    @Override
    public TransactionResponse submit(final UUID correlationId, final User userContext, final String... args) throws ContractException, TimeoutException, InterruptedException {
        return createSubmit(correlationId, userContext, args);
    }

    @Override
    public TransactionResponse submit(final UUID correlationId, final String... args) throws ContractException, TimeoutException, InterruptedException {
        return createSubmit(correlationId, null, args);
    }

    private TransactionResponse createSubmit(final UUID correlationId, @Nullable final User userContext, final String... args) throws ContractException, TimeoutException, InterruptedException {
            Collection<ProposalResponse> proposalResponses = endorseTransaction(userContext, args);
            Collection<ProposalResponse> validResponses = validatePeerResponses(proposalResponses);

        try {
            byte[] result = commitTransaction(validResponses);
            return new TransactionResponse(correlationId, proposalResponses.iterator().next().getTransactionID(), result);
        } catch (ContractException e) {
            e.setProposalResponses(proposalResponses);
            throw e;
        }
    }

    private Collection<ProposalResponse> endorseTransaction(final User userContext, final String... args) {
        try {
            TransactionProposalRequest request = newProposalRequest(userContext, args);
            return sendTransactionProposal(request);
        } catch (InvalidArgumentException | ProposalException | ServiceDiscoveryException e) {
            throw new GatewayRuntimeException(e);
        }
    }

    private Collection<ProposalResponse> retryProposal(final TransactionProposalRequest request, final Channel.DiscoveryOptions discoveryOptions)
            throws ProposalException, InvalidArgumentException, ServiceDiscoveryException {
        int retryCount = channel.getPeers().size();
        while (true) {
            try {
                return channel.sendTransactionProposalToEndorsers(request, discoveryOptions);
            } catch (InvalidArgumentException | ServiceDiscoveryException | ProposalException e) {
                LOG.info("Retrying " + channel.getName() + "." + request.getChaincodeName() + "." + request.getFcn()+
                        ": "+e.getMessage());
                if (retryCount <= 0) {
                    throw e;
                }
            }
            retryCount--;
        }
    }

    private Collection<ProposalResponse> sendTransactionProposal(final TransactionProposalRequest request)
            throws ProposalException, InvalidArgumentException, ServiceDiscoveryException {
        if (endorsingPeers != null) {
            return channel.sendTransactionProposal(request, endorsingPeers);
        } else if (network.getGateway().isDiscoveryEnabled()) {
            Channel.DiscoveryOptions discoveryOptions = createDiscoveryOptions()
                    .setEndorsementSelector(ServiceDiscovery.EndorsementSelector.ENDORSEMENT_SELECTION_RANDOM)
                    .setInspectResults(true)
                    .setForceDiscovery(true);
            //return retryProposal(request, discoveryOptions);
            return channel.sendTransactionProposalToEndorsers(request, discoveryOptions);

        } else {
            return channel.sendTransactionProposal(request);
        }
    }


    private byte[] commitTransaction(final Collection<ProposalResponse> validResponses)
            throws TimeoutException, ContractException, InterruptedException {
        ProposalResponse proposalResponse = validResponses.iterator().next();
        String transactionId = proposalResponse.getTransactionID();

        CommitHandler commitHandler = commitHandlerFactory.create(transactionId, network);
        commitHandler.startListening();

        try {
            Channel.TransactionOptions transactionOptions = Channel.TransactionOptions.createTransactionOptions()
                    .nOfEvents(Channel.NOfEvents.createNoEvents()); // Disable default commit wait behaviour
            channel.sendTransaction(validResponses, transactionOptions)
                    .get(DEFAULT_ORDERER_TIMEOUT, DEFAULT_ORDERER_TIMEOUT_UNIT);
        } catch (TimeoutException e) {
            commitHandler.cancelListening();
            throw e;
        } catch (Exception e) {
            commitHandler.cancelListening();
            throw new ContractException("Failed to send transaction to the orderer", e);
        }

        commitHandler.waitForEvents(commitTimeout.getTime(), commitTimeout.getTimeUnit());

        try {
            return proposalResponse.getChaincodeActionResponsePayload();
        } catch (InvalidArgumentException e) {
            throw new GatewayRuntimeException(e);
        }
    }

    private TransactionProposalRequest newProposalRequest(@Nullable final User userContext, final String... args) {
        TransactionProposalRequest request = network.getGateway().getClient().newTransactionProposalRequest();
        configureRequest(userContext, request, args);
        if (transientData != null) {
            try {
                request.setTransientMap(transientData);
            } catch (InvalidArgumentException e) {
                // Only happens if transientData is null
                throw new IllegalStateException(e);
            }
        }
        return request;
    }

    private void configureRequest(@Nullable final User userContext, final TransactionRequest request, final String... args) {
        request.setChaincodeID(getChaincodeId());
        request.setFcn(name);
        request.setArgs(args);
        if (userContext != null) {
            request.setUserContext(userContext);
        }
    }

    private ChaincodeID getChaincodeId() {
        return ChaincodeID.newBuilder()
                .setName(contract.getChaincodeId())
                .build();
    }

    private Collection<ProposalResponse> validatePeerResponses(final Collection<ProposalResponse> proposalResponses) throws ContractException {
        final Collection<ProposalResponse> validResponses = new ArrayList<>();
        final Collection<String> invalidResponseMsgs = new ArrayList<>();
        proposalResponses.forEach(response -> {
            String peerUrl = response.getPeer() != null ? response.getPeer().getUrl() : "<unknown>";
            if (response.getStatus().equals(ChaincodeResponse.Status.SUCCESS)) {
                LOG.debug(String.format("validatePeerResponses: valid response from peer %s", peerUrl));
                validResponses.add(response);
            } else {
                LOG.warn(String.format("validatePeerResponses: invalid response from peer %s, message %s", peerUrl, response.getMessage()));
                invalidResponseMsgs.add(response.getMessage());
            }
        });

        if (validResponses.size() < 1) {
            String msg = String.format("No valid proposal responses received. %d peer error responses: %s",
                    invalidResponseMsgs.size(), String.join("; ", invalidResponseMsgs));
            LOG.error(msg);
            throw new ContractException(msg, proposalResponses);
        }

        return validResponses;
    }

    @Override
    public TransactionResponse evaluate(final UUID correlationId, final User userContext, final String... args) throws ContractException {
        return processEvaluate(correlationId, userContext, args);
    }

    @Override
    public TransactionResponse evaluate(final UUID correlationId, final String... args) throws ContractException {
        return processEvaluate(correlationId, null, args);
    }

    private TransactionResponse processEvaluate(final UUID correlationId, @Nullable final User userContext, final String... args) throws ContractException {
        QueryByChaincodeRequest request = newQueryRequest(userContext, args);
        Query query = new QueryImpl(network.getChannel(), request);

        ProposalResponse response = queryHandler.evaluate(query);

        try {
            return new TransactionResponse(correlationId, response.getTransactionID(), response.getChaincodeActionResponsePayload());
        } catch (InvalidArgumentException e) {
            throw new ContractException(response.getMessage(), e);
        }
    }

    private QueryByChaincodeRequest newQueryRequest(@Nullable final User userContext, final String... args) {
        QueryByChaincodeRequest request = gateway.getClient().newQueryProposalRequest();
        configureRequest(userContext, request, args);
        if (transientData != null) {
            try {
                request.setTransientMap(transientData);
            } catch (InvalidArgumentException e) {
                // Only happens if transientData is null
                throw new IllegalStateException(e);
            }
        }
        return request;
    }
}
