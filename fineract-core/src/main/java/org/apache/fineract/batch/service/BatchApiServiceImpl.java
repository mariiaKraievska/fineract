/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.batch.service;

import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPathException;
import io.github.resilience4j.core.functions.Either;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.batch.command.CommandContext;
import org.apache.fineract.batch.command.CommandStrategy;
import org.apache.fineract.batch.command.CommandStrategyProvider;
import org.apache.fineract.batch.domain.BatchRequest;
import org.apache.fineract.batch.domain.BatchResponse;
import org.apache.fineract.batch.domain.Header;
import org.apache.fineract.batch.exception.BatchReferenceInvalidException;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.batch.service.ResolutionHelper.BatchRequestNode;
import org.apache.fineract.infrastructure.core.domain.BatchRequestContextHolder;
import org.apache.fineract.infrastructure.core.exception.AbstractIdempotentCommandException;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.filters.BatchCallHandler;
import org.apache.fineract.infrastructure.core.filters.BatchFilter;
import org.apache.fineract.infrastructure.core.filters.BatchRequestPreprocessor;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementation for {@link BatchApiService} to iterate through all the incoming requests and obtain the appropriate
 * CommandStrategy from CommandStrategyProvider.
 *
 * @author Rishabh Shukla
 * @see org.apache.fineract.batch.domain.BatchRequest
 * @see org.apache.fineract.batch.domain.BatchResponse
 * @see org.apache.fineract.batch.command.CommandStrategyProvider
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchApiServiceImpl implements BatchApiService {

    private final CommandStrategyProvider strategyProvider;
    private final ResolutionHelper resolutionHelper;
    private final PlatformTransactionManager transactionManager;
    private final ErrorHandler errorHandler;

    private final List<BatchFilter> batchFilters;

    private final List<BatchRequestPreprocessor> batchPreprocessors;

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * Run each request root step in a separated transaction
     *
     * @param requestList
     * @param uriInfo
     * @return
     */
    @Override
    public List<BatchResponse> handleBatchRequestsWithoutEnclosingTransaction(final List<BatchRequest> requestList, UriInfo uriInfo) {
        BatchRequestContextHolder.setEnclosingTransaction(Optional.empty());
        return handleBatchRequests(false, requestList, uriInfo);
    }

    /**
     * Run the batch request in transaction
     *
     * @param requestList
     * @param uriInfo
     * @return
     */
    @Override
    public List<BatchResponse> handleBatchRequestsWithEnclosingTransaction(final List<BatchRequest> requestList, final UriInfo uriInfo) {
        return callInTransaction(Function.identity()::apply, () -> handleBatchRequests(true, requestList, uriInfo), true);
    }

    /**
     * Helper method to run the command in transaction
     *
     * @param request
     *            the enclosing supplier of the command
     * @param transactionConfigurator
     *            consumer to configure the transaction behavior and isolation
     * @param enclosingTransaction
     *            a boolean parameter that indicates whether the current operation is part of an enclosing transaction
     * @return
     */
    private List<BatchResponse> callInTransaction(Consumer<TransactionTemplate> transactionConfigurator,
            Supplier<List<BatchResponse>> request, final boolean enclosingTransaction) {
        List<BatchResponse> responseList = new ArrayList<>();
        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionConfigurator.accept(transactionTemplate);
            return transactionTemplate.execute(status -> {
                if (enclosingTransaction) {
                    BatchRequestContextHolder.setEnclosingTransaction(Optional.of(status));
                }
                try {
                    responseList.addAll(request.get());
                    return responseList;
                } catch (BatchExecutionException ex) {
                    status.setRollbackOnly();
                    return List.of(buildErrorResponse(ex.getCause(), ex.getRequest()));
                } catch (RuntimeException ex) {
                    status.setRollbackOnly();
                    return buildErrorResponses(ex, responseList);
                } finally {
                    BatchRequestContextHolder.setEnclosingTransaction(Optional.empty());
                }
            });
        } catch (TransactionException | NonTransientDataAccessException ex) {
            return buildErrorResponses(ex, responseList);
        }
    }

    /**
     * Returns the response list by getting a proper {@link org.apache.fineract.batch.command.CommandStrategy}.
     * execute() method of acquired commandStrategy is then provided with the separate Request.
     *
     * @param requestList
     * @param uriInfo
     * @return {@code List<BatchResponse>}
     */
    private List<BatchResponse> handleBatchRequests(boolean enclosingTransaction, final List<BatchRequest> requestList,
            final UriInfo uriInfo) {
        final List<BatchRequestNode> rootNodes;
        try {
            rootNodes = this.resolutionHelper.buildNodesTree(requestList);
        } catch (BatchReferenceInvalidException e) {
            return List.of(buildErrorResponse(e));
        }

        final ArrayList<BatchResponse> responseList = new ArrayList<>(requestList.size());
        for (BatchRequestNode rootNode : rootNodes) {
            if (enclosingTransaction) {
                this.callRequestRecursive(rootNode.getRequest(), rootNode, responseList, uriInfo, enclosingTransaction);
            } else {
                ArrayList<BatchResponse> localResponseList = new ArrayList<>();
                this.callRequestRecursive(rootNode.getRequest(), rootNode, localResponseList, uriInfo, enclosingTransaction);
                responseList.addAll(localResponseList);
            }
        }
        responseList.sort(Comparator.comparing(BatchResponse::getRequestId));
        return responseList;
    }

    /**
     * Executes the request and call child requests recursively.
     *
     * @param request
     *            the current batch request
     * @param requestNode
     *            the batch request holder node
     * @param responseList
     *            the collected responses
     * @return {@code BatchResponse}
     */
    private void callRequestRecursive(BatchRequest request, BatchRequestNode requestNode, List<BatchResponse> responseList, UriInfo uriInfo,
            boolean enclosingTransaction) {
        // run current node
        BatchResponse response;
        if (enclosingTransaction) {
            response = executeRequest(request, uriInfo);
        } else {
            List<BatchResponse> transactionResponse = callInTransaction(
                    transactionTemplate -> transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW),
                    () -> List.of(executeRequest(request, uriInfo)), false);
            response = transactionResponse.get(0);
        }
        responseList.add(response);
        if (response.getStatusCode() != null && response.getStatusCode() == SC_OK) {
            // run child nodes
            requestNode.getChildNodes().forEach(childNode -> {
                BatchRequest childRequest = childNode.getRequest();
                BatchRequest resolvedChildRequest;
                try {
                    resolvedChildRequest = this.resolutionHelper.resolveRequest(childRequest, response);
                } catch (JsonPathException jpex) {
                    responseList.add(buildErrorResponse(jpex, childRequest));
                    return;
                } catch (RuntimeException ex) {
                    throw new BatchExecutionException(childRequest, ex);
                }
                callRequestRecursive(resolvedChildRequest, childNode, responseList, uriInfo, enclosingTransaction);
            });
        } else {
            responseList.addAll(parentRequestFailedRecursive(request, requestNode, response, null));
        }
        // If the current request fails, then all the child requests are not executed. If we want to write out all the
        // child requests, here is the place.
    }

    /**
     * Execute the request
     *
     * @param request
     * @param uriInfo
     * @return
     */
    private BatchResponse executeRequest(BatchRequest request, UriInfo uriInfo) {
        final CommandStrategy commandStrategy = this.strategyProvider
                .getCommandStrategy(CommandContext.resource(request.getRelativeUrl()).method(request.getMethod()).build());
        log.debug("Batch request: method [{}], relative url [{}]", request.getMethod(), request.getRelativeUrl());
        Either<RuntimeException, BatchRequest> preprocessorResult = runPreprocessors(request);
        if (preprocessorResult.isLeft()) {
            throw new BatchExecutionException(request, preprocessorResult.getLeft());
        } else {
            request = preprocessorResult.get();
        }
        try {
            BatchRequestContextHolder.setRequestAttributes(new HashMap<>(Optional.ofNullable(request.getHeaders())
                    .map(list -> list.stream().collect(Collectors.toMap(Header::getName, Header::getValue)))
                    .orElse(Collections.emptyMap())));
            BatchCallHandler callHandler = new BatchCallHandler(this.batchFilters, commandStrategy::execute);
            Optional<TransactionStatus> transaction = BatchRequestContextHolder.getEnclosingTransaction();
            if (transaction.isPresent()) {
                entityManager.flush();
            }
            final BatchResponse rootResponse = callHandler.serviceCall(request, uriInfo);
            log.debug("Batch response: status code [{}], method [{}], relative url [{}]", rootResponse.getStatusCode(), request.getMethod(),
                    request.getRelativeUrl());
            return rootResponse;
        } catch (AbstractIdempotentCommandException idempotentException) {
            return buildErrorResponse(idempotentException, request);
        } catch (RuntimeException ex) {
            throw new BatchExecutionException(request, ex);
        } finally {
            BatchRequestContextHolder.resetRequestAttributes();
        }
    }

    private Either<RuntimeException, BatchRequest> runPreprocessors(BatchRequest request) {
        return runPreprocessor(batchPreprocessors, request);
    }

    private Either<RuntimeException, BatchRequest> runPreprocessor(List<BatchRequestPreprocessor> remainingPreprocessor,
            BatchRequest request) {
        if (remainingPreprocessor.isEmpty()) {
            return Either.right(request);
        } else {
            BatchRequestPreprocessor preprocessor = remainingPreprocessor.get(0);
            Either<RuntimeException, BatchRequest> processingResult = preprocessor.preprocess(request);
            if (processingResult.isLeft()) {
                return processingResult;
            } else {
                return runPreprocessor(remainingPreprocessor.subList(1, remainingPreprocessor.size()), processingResult.get());
            }
        }
    }

    /**
     * All requests recursively are set to status 409 if the parent request fails.
     *
     * @param request
     *            the current request
     * @param requestNode
     *            the current request node
     * @return {@code BatchResponse} list of the generated batch responses
     */
    private List<BatchResponse> parentRequestFailedRecursive(@NotNull BatchRequest request, @NotNull BatchRequestNode requestNode,
            @NotNull BatchResponse response, Long parentId) {
        List<BatchResponse> responseList = new ArrayList<>();
        if (parentId == null) { // root
            BatchRequestContextHolder.getEnclosingTransaction().ifPresent(TransactionExecution::setRollbackOnly);
        } else {
            responseList.add(buildErrorResponse(request.getRequestId(), response.getStatusCode(),
                    "Parent request with id " + parentId + " was erroneous!", null));
        }
        requestNode.getChildNodes().forEach(childNode -> responseList
                .addAll(parentRequestFailedRecursive(childNode.getRequest(), childNode, response, request.getRequestId())));
        return responseList;
    }

    @NotNull
    private BatchResponse buildErrorResponse(@NotNull Throwable ex) {
        return buildErrorResponse(ex, null);
    }

    /**
     * Return the response when any exception raised
     *
     * @param ex
     *            the exception
     * @param request
     *            the called request
     */
    private BatchResponse buildErrorResponse(Throwable ex, BatchRequest request) {
        Long requestId = null;
        Integer statusCode = null;
        String body = null;
        Set<Header> headers = new HashSet<>();
        if (ex != null) {
            ErrorInfo errorInfo = errorHandler.handle(ErrorHandler.getMappable(ex));
            statusCode = errorInfo.getStatusCode();
            body = errorInfo.getMessage();
            headers = Optional.ofNullable(errorInfo.getHeaders()).orElse(new HashSet<>());
        }
        if (request != null) {
            requestId = request.getRequestId();
            if (request.getHeaders() != null) {
                headers.addAll(request.getHeaders());
            }
        }
        return buildErrorResponse(requestId, statusCode, body, headers);
    }

    @NotNull
    private List<BatchResponse> buildErrorResponses(Throwable ex, @NotNull List<BatchResponse> responseList) {
        BatchResponse response = responseList.isEmpty() ? null
                : responseList.stream().filter(e -> e.getStatusCode() == null || e.getStatusCode() != SC_OK).findFirst()
                        .orElse(responseList.get(responseList.size() - 1));

        if (response != null && response.getStatusCode() == SC_OK && ex instanceof TransactionSystemException tse) {
            ex = new ConcurrencyFailureException(tse.getMessage(), tse.getCause());
        }

        Long requestId = null;
        Integer statusCode = null;
        String body = null;
        Set<Header> headers = new HashSet<>();
        if (ex != null) {
            ErrorInfo errorInfo = errorHandler.handle(ErrorHandler.getMappable(ex));
            statusCode = errorInfo.getStatusCode();
            body = errorInfo.getMessage();
            headers = Optional.ofNullable(errorInfo.getHeaders()).orElse(new HashSet<>());
        }
        if (response != null) {
            requestId = response.getRequestId();
            if (response.getStatusCode() == null || response.getStatusCode() != SC_OK) {
                if (response.getStatusCode() != null) {
                    statusCode = response.getStatusCode();
                }
                body = "Transaction is being rolled back. First erroneous request: \n" + new Gson().toJson(response);
            }
            if (response.getHeaders() != null) {
                headers.addAll(response.getHeaders());
            }
        }
        return List.of(buildErrorResponse(requestId, statusCode, body, headers));
    }

    private BatchResponse buildErrorResponse(Long requestId, Integer statusCode, String body, Set<Header> headers) {
        return new BatchResponse().setRequestId(requestId).setStatusCode(statusCode == null ? SC_INTERNAL_SERVER_ERROR : statusCode)
                .setBody(body == null ? "Request with id " + requestId + " was erroneous!" : body).setHeaders(headers);
    }
}
