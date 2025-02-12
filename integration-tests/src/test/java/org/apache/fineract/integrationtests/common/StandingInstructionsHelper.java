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
package org.apache.fineract.integrationtests.common;

import com.google.gson.Gson;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SuppressWarnings({ "unused", "rawtypes", "unchecked" })
public class StandingInstructionsHelper {

    private static final String STANDING_INSTRUCTIONS_URL = "/fineract-provider/api/v1/standinginstructions";
    private static final String STANDING_INSTRUCTIONS_RUN_HISTORY_URL = "/fineract-provider/api/v1/standinginstructionrunhistory";
    private static final String LOCALE = "en_GB";
    private static final String OFFICE_ID = "1";
    private static final String INSTRUCTION_TYPE_FIXED = "1";
    private static final String INSTRUCTION_TYPE_DUES = "2";
    private static final String PRIORITY_URGENT = "1";
    private static final String PRIORITY_HIGH = "2";
    private static final String PRIORITY_MEDIUM = "3";
    private static final String PRIORITY_LOW = "4";
    private static final String RECURRENCE_FREQUENCY_DAYS = "0";
    private static final String RECURRENCE_FREQUENCY_WEEKS = "1";
    private static final String RECURRENCE_FREQUENCY_MONTHS = "2";
    private static final String RECURRENCE_FREQUENCY_YEARS = "3";
    private static final String RECURRENCE_TYPE_PERIODIC = "1";
    private static final String RECURRENCE_TYPE_AS_PER_DUES = "2";
    private static final String STATUS_ACTIVE = "1";
    private static final String STATUS_DISABLED = "2";
    private static final String TRANSFER_TYPE_ACCOUNT_TRANSFER = "1";
    private static final String TRANSFER_TYPE_LOAN_REPAYMENT = "2";
    private static final String ACCOUNT_TRANSFER_DATE = "01 March 2013";

    private String transferDate = "";
    private String officeId = OFFICE_ID;

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public StandingInstructionsHelper(final RequestSpecification requestSpec, final ResponseSpecification responseSpec) {
        this.requestSpec = requestSpec;
        this.responseSpec = responseSpec;
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public String build(final String clientId, final String fromAccountId, final String toAccountId, final String fromAccountType,
            final String toAccountType, final String validFrom, final String validTo, final String monthDay) {

        final HashMap<String, String> map = new HashMap<>();
        map.put("name", Utils.uniqueRandomStringGenerator("STANDING_INSTRUCTION_", 5));
        map.put("dateFormat", "dd MMMM yyyy");
        map.put("monthDayFormat", "dd MMMM");
        map.put("locale", LOCALE);
        map.put("fromClientId", clientId);
        map.put("fromAccountId", fromAccountId);
        map.put("fromAccountType", fromAccountType);
        map.put("fromOfficeId", this.officeId);
        map.put("toClientId", clientId);
        map.put("toAccountId", toAccountId);
        map.put("toAccountType", toAccountType);
        map.put("toOfficeId", this.officeId);
        map.put("amount", "500");
        map.put("transferType", TRANSFER_TYPE_ACCOUNT_TRANSFER);
        map.put("priority", PRIORITY_URGENT);
        map.put("status", STATUS_ACTIVE);
        map.put("instructionType", INSTRUCTION_TYPE_FIXED);
        map.put("validFrom", validFrom);
        map.put("validTill", validTo);
        map.put("recurrenceType", RECURRENCE_TYPE_PERIODIC);
        map.put("recurrenceInterval", "1");
        map.put("recurrenceFrequency", RECURRENCE_FREQUENCY_WEEKS);
        map.put("recurrenceOnMonthDay", monthDay);
        String savingsApplicationJSON = new Gson().toJson(map);
        log.info("{}", savingsApplicationJSON);
        return savingsApplicationJSON;
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public Integer createStandingInstruction(final String clientId, final String fromAccountId, final String toAccountId,
            final String fromAccountType, final String toAccountType, final String validFrom, final String validTo, final String monthDay) {
        log.info("-------------------------------- CREATE STANDING INSTRUCTIONS --------------------------------");
        final String standingInstructionAsJSON = new StandingInstructionsHelper(this.requestSpec, this.responseSpec).build(clientId,
                fromAccountId, toAccountId, fromAccountType, toAccountType, validFrom, validTo, monthDay);
        return Utils.performServerPost(this.requestSpec, this.responseSpec, STANDING_INSTRUCTIONS_URL + "?" + Utils.TENANT_IDENTIFIER,
                standingInstructionAsJSON, "resourceId");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public HashMap getStandingInstructionById(final String standingInstructionId) {

        log.info("----------------------------- RETRIEVING STANDING INSTRUCTION BY ID---------------------------");
        final String GET_STANDING_INSTRUCTION_BY_ID_URL = STANDING_INSTRUCTIONS_URL + "/" + standingInstructionId + "?"
                + Utils.TENANT_IDENTIFIER;
        return Utils.performServerGet(this.requestSpec, this.responseSpec, GET_STANDING_INSTRUCTION_BY_ID_URL, "");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public List<HashMap> getStandingInstructionHistory(Integer fromSavingsId, Integer fromAccountType, Integer fromClientId,
            Integer transferType) {
        final String STANDING_INSTRUCTIONS_HISTORY_URL = STANDING_INSTRUCTIONS_RUN_HISTORY_URL + "?" + Utils.TENANT_IDENTIFIER
                + "&fromSavingsId=" + fromSavingsId + "&fromAccountType=" + fromAccountType + "&clientId=" + fromClientId + "&transferType="
                + transferType;
        log.info("STANDING_INSTRUCTIONS_HISTORY_URL= {}", STANDING_INSTRUCTIONS_HISTORY_URL);
        return Utils.performServerGet(this.requestSpec, this.responseSpec, STANDING_INSTRUCTIONS_HISTORY_URL, "pageItems");
    }
}
