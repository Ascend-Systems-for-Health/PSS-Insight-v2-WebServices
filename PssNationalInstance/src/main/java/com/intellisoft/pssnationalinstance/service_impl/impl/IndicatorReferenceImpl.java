/*
 * PSS Insight v2.0 — Derivative Work
 * Copyright (C) 2024-2025 Ascend Systems for Health.
 *
 * Based on PSS Insight v2.0, originally developed by IntelliSOFT Consulting
 * Limited under the USAID MTaPS Program (implemented by Management Sciences
 * for Health). Built on DHIS2 (https://dhis2.org, BSD 3-Clause).
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.intellisoft.pssnationalinstance.service_impl.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellisoft.pssnationalinstance.*;
import com.intellisoft.pssnationalinstance.model.Response;
import com.intellisoft.pssnationalinstance.util.AppConstants;
import com.intellisoft.pssnationalinstance.service_impl.service.IndicatorReferenceService;
import com.intellisoft.pssnationalinstance.util.EnvUrlConstants;
import com.intellisoft.pssnationalinstance.util.GenericWebclient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Log4j2
@RequiredArgsConstructor
@Service
public class IndicatorReferenceImpl implements IndicatorReferenceService {

    private final EnvUrlConstants envUrlConstants;
    private final FormatterClass formatterClass = new FormatterClass();
    // Spring-managed ObjectMapper (carries the ParameterNamesModule that the
    // remote WebClient deserialization already relies on) — used by the local
    // Hub-fallback to map dataStore content into DbIndicatorDetails.
    private final ObjectMapper objectMapper;
    @Value("${dhis.username}")
    private String username;
    @Value("${dhis.password}")
    private String password;

    // Known placeholder/test record present in the local master_indicator_templates
    // metadata; excluded from the Hub-fallback so only genuine indicators surface.
    private static final String JUNK_INDICATOR_CODE = "nfkjevew";

    @Override
    public Results addIndicatorDictionary(DbIndicatorDetails dbIndicatorDetails) {
        try {
            // Generate UUID
            String uuid = UUID.randomUUID().toString();

            // Add timestamp to dbIndicatorDetails object
            Instant now = Instant.now();
            Date date = Date.from(now);

            dbIndicatorDetails.setUuid(uuid);
            dbIndicatorDetails.setDate(date);

            String url = AppConstants.INDICATOR_DESCRIPTIONS;

            // Retrieve existing JSON collection of Indicator_References from the INDICATOR_DESCRIPTIONS API
            List<DbIndicatorDetails> responseList = fetchExistingIndicatorDetails(url);

            if (responseList != null && !responseList.isEmpty()) {
                // Append new indicator details to the existing data dictionary
                responseList.add(dbIndicatorDetails);

                // Convert the updated list to JSON
                String updatedIndicatorList = convertToJson(responseList);

                // Send a PUT request to update the Indicator_References API with the updated JSON data
                if (updateIndicatorDetails(url, updatedIndicatorList)) {
                    return new Results(200, new DbDetails("The indicator values have been added."));
                }
            }

            return new Results(400, "There was an issue processing your request.");
        } catch (IOException | URISyntaxException e) {
            log.error("There was an issue adding indicator values to dictionary");
            return new Results(400, "Please try again after some time");
        }
    }

    private List<DbIndicatorDetails> fetchExistingIndicatorDetails(String url) throws URISyntaxException {
        Flux<DbIndicatorDetails> indicatorFlux = GenericWebclient.getForCollectionResponse(url, DbIndicatorDetails.class);
        return indicatorFlux.collectList().block();
    }

    private String convertToJson(List<DbIndicatorDetails> indicatorList) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(indicatorList);
    }

    private boolean updateIndicatorDetails(String url, String updatedIndicatorList) throws URISyntaxException {
        var response = GenericWebclient.putForSingleObjResponse(url, updatedIndicatorList, String.class, Response.class);
        return response.getHttpStatusCode() == 200;
    }


    @Override
    public Results listIndicatorDictionary() {

        try {
            return new Results(200, resolveIndicatorReferences());
        } catch (Exception e) {
            log.error("There was an issue Fetching indicators from the data dictionary", e);
        }


        return new Results(400, "There was an issue with the request. Try again later.");
    }

    /**
     * Resolve the indicator dictionary with a remote-first, local-fallback strategy.
     *
     * <p>1. Primary path (unchanged): fetch from the remote International/Hub instance
     * via {@link #getIndicatorList()} ({@code INTERNATIONAL_BASE_URL}). When a Hub is
     * deployed and reachable this returns the published dictionary and is used verbatim —
     * the fallback below is never consulted, so the original behaviour resumes
     * automatically with no further code change.
     *
     * <p>2. Additive fallback: when the Hub is unreachable (connection failure) or returns
     * an empty list — as is the case before a Hub is provisioned — surface the genuine
     * indicator framework already published in this national instance's own DHIS2 dataStore
     * namespace {@code master_indicator_templates} (latest version), mapped to the same
     * response shape the Indicator Dictionary UI expects.
     */
    private List<DbIndicatorDetails> resolveIndicatorReferences() {
        List<DbIndicatorDetails> remoteList = getIndicatorList();
        if (remoteList != null && !remoteList.isEmpty()) {
            return remoteList;
        }
        log.info("Indicator dictionary Hub source unavailable/empty — falling back to local master_indicator_templates.");
        return getIndicatorListFromLocalTemplate();
    }

    /**
     * Hub-fallback source: read the latest published version of the local
     * {@code master_indicator_templates} dataStore namespace and return its
     * {@code indicatorDescriptions}, excluding placeholder/test records.
     */
    private List<DbIndicatorDetails> getIndicatorListFromLocalTemplate() {
        try {
            String localBaseUrl = envUrlConstants.getNATIONAL_PUBLISHED_VERSIONS();
            String latestVersion = getLatestLocalTemplateVersion(localBaseUrl);
            if (latestVersion == null) {
                log.warn("No local master_indicator_templates versions found for Hub-fallback.");
                return Collections.emptyList();
            }

            DbMetadataJson dbMetadataJson = getLocalMetadata(localBaseUrl + latestVersion);
            if (dbMetadataJson == null || dbMetadataJson.getMetadata() == null) {
                return Collections.emptyList();
            }

            Object descriptions = dbMetadataJson.getMetadata().getIndicatorDescriptions();
            if (descriptions == null) {
                return Collections.emptyList();
            }

            List<DbIndicatorDetails> mapped = objectMapper.convertValue(
                    descriptions, new TypeReference<List<DbIndicatorDetails>>() {
                    });

            List<DbIndicatorDetails> cleaned = new ArrayList<>();
            for (DbIndicatorDetails indicator : mapped) {
                Object codeObj = indicator.getIndicatorCode();
                String code = codeObj != null ? codeObj.toString().trim() : "";
                if (code.isEmpty() || JUNK_INDICATOR_CODE.equalsIgnoreCase(code)) {
                    continue; // skip placeholder/test records
                }
                cleaned.add(indicator);
            }

            log.info("Indicator dictionary Hub-fallback: returning {} indicators from local master_indicator_templates/{}.",
                    cleaned.size(), latestVersion);
            return cleaned;
        } catch (Exception e) {
            log.error("An error occurred while reading the local master_indicator_templates Hub-fallback", e);
        }
        return Collections.emptyList();
    }

    /**
     * Fetch a single local master_indicator_templates version document as
     * {@link DbMetadataJson}. A published template version is large (&gt;1&nbsp;MB),
     * so the default 256&nbsp;KB WebClient in-memory buffer is raised here. The shared
     * {@link #getMetadata(String)} is intentionally left untouched for the Hub /
     * update / delete paths.
     */
    private DbMetadataJson getLocalMetadata(String url) {
        try {
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth);

            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                    .build();

            return WebClient.builder()
                    .baseUrl(url)
                    .exchangeStrategies(strategies)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                    .build()
                    .get().retrieve().bodyToMono(DbMetadataJson.class).block();
        } catch (Exception e) {
            log.error("An error occurred while reading the local template metadata", e);
        }
        return null;
    }

    /**
     * Resolve the highest numeric version key in the local master_indicator_templates
     * namespace (e.g. "411"). Non-numeric keys (e.g. "vv2", "v15") are ignored.
     */
    private String getLatestLocalTemplateVersion(String namespaceUrl) {
        try {
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth);

            List<?> keys = WebClient.builder().baseUrl(namespaceUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader).build()
                    .get().retrieve().bodyToMono(List.class).block();

            if (keys == null || keys.isEmpty()) {
                return null;
            }

            Integer maxVersion = null;
            for (Object key : keys) {
                try {
                    int v = Integer.parseInt(String.valueOf(key).trim());
                    if (maxVersion == null || v > maxVersion) {
                        maxVersion = v;
                    }
                } catch (NumberFormatException ignored) {
                    // non-numeric version keys are not eligible
                }
            }
            return maxVersion != null ? String.valueOf(maxVersion) : null;
        } catch (Exception e) {
            log.error("An error occurred while resolving the latest local template version", e);
        }
        return null;
    }

    private List<DbIndicatorDetails> getIndicatorList() {
        try {
            //Auth-headers:
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth);

            String url = envUrlConstants.getINDICATOR_DESCRIPTIONS();

            //Get metadata json
            Flux<DbIndicatorDetails> responseFlux = WebClient.builder().baseUrl(url).defaultHeader(HttpHeaders.AUTHORIZATION, authHeader).build().get().retrieve().bodyToFlux(DbIndicatorDetails.class);

            List<DbIndicatorDetails> responseList = responseFlux.collectList().block();


            if (responseList != null) {

                List<DbIndicatorDetails> dbIndicatorDicList = new ArrayList<>();

                for (DbIndicatorDetails dataElements : responseList) {

                    String Description = dataElements.getDescription();
                    String Indicator_Code = dataElements.getIndicator_Code();


                    dbIndicatorDicList.add(dataElements);
                }


                return dbIndicatorDicList;
            }

        } catch (Exception e) {
            log.error("An error occurred while fetching indicator descriptions");
        }
        return Collections.emptyList();

    }

    @Override
    public Results getIndicatorValues(String uid) {

        DbIndicatorDetails dbIndicatorDetails = getIndicator(uid);
        if (dbIndicatorDetails != null) {
            return new Results(200, dbIndicatorDetails);
        }

        return new Results(400, "Resource not found");
    }

    private DbIndicatorDetails getIndicator(String uid) {
        List<DbIndicatorDetails> dbIndicatorDetailsList = resolveIndicatorReferences();
        for (DbIndicatorDetails dbIndicatorDetails : dbIndicatorDetailsList) {

            String uuid = (String) dbIndicatorDetails.getUuid();
            if (uid.equals(uuid)) {
                return dbIndicatorDetails;
            }

        }
        return null;
    }

    @Override
    public Results updateDictionary(DbIndicatorDetails dbIndicatorDetails) {

        /**
         * TODO: UPDATE ASSESSMENT QUESTIONS
         */

        try {

            String indicatorName = (String) dbIndicatorDetails.getIndicatorName();
            String indicatorCode = (String) dbIndicatorDetails.getIndicatorCode();
            String dataType = (String) dbIndicatorDetails.getDataType();
            String topic = (String) dbIndicatorDetails.getTopic();
            String definition = (String) dbIndicatorDetails.getDefinition();
            List<DbAssessmentQuestion> assessmentQuestions = dbIndicatorDetails.getAssessmentQuestions();
            String purposeAndIssues = (String) dbIndicatorDetails.getPurposeAndIssues();
            String preferredDataSources = (String) dbIndicatorDetails.getPreferredDataSources();
            String methodOfEstimation = (String) dbIndicatorDetails.getMethodOfEstimation();
            String proposedScoring = (String) dbIndicatorDetails.getProposedScoring();
            String expectedFrequencyDataDissemination = (String) dbIndicatorDetails.getExpectedFrequencyDataDissemination();
            String indicatorReference = (String) dbIndicatorDetails.getIndicatorReference();
            DbCreatedBy createdBy = dbIndicatorDetails.getCreatedBy();


            String uid = (String) dbIndicatorDetails.getUuid();
            if (uid != null) {
                DbIndicatorDetails indicatorDetails = null;
                List<DbIndicatorDetails> dbIndicatorDetailsList = getIndicatorList();
                for (DbIndicatorDetails details : dbIndicatorDetailsList) {

                    String uuid = (String) details.getUuid();
                    if (uid.equals(uuid)) {
                        indicatorDetails = details;
                        dbIndicatorDetailsList.remove(details);
                        break;
                    }

                }

                if (indicatorDetails != null) {

                    if (indicatorName != null) indicatorDetails.setIndicatorName(indicatorName);
                    if (indicatorCode != null) indicatorDetails.setIndicatorCode(indicatorCode);
                    if (dataType != null) indicatorDetails.setDate(dataType);
                    if (topic != null) indicatorDetails.setTopic(topic);
                    if (definition != null) indicatorDetails.setDefinition(definition);
                    if (purposeAndIssues != null) indicatorDetails.setPurposeAndIssues(purposeAndIssues);
                    if (preferredDataSources != null) indicatorDetails.setPreferredDataSources(preferredDataSources);
                    if (methodOfEstimation != null) indicatorDetails.setMethodOfEstimation(methodOfEstimation);
                    if (proposedScoring != null) indicatorDetails.setProposedScoring(proposedScoring);
                    if (expectedFrequencyDataDissemination != null)
                        indicatorDetails.setExpectedFrequencyDataDissemination(expectedFrequencyDataDissemination);
                    if (indicatorReference != null) indicatorDetails.setIndicatorReference(indicatorReference);

                    String publishedBaseUrl = envUrlConstants.getDATA_STORE_ENDPOINT();
                    int publishedVersionNo = getVersions(publishedBaseUrl);

                    String auth = username + ":" + password;
                    byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                    String authHeader = "Basic " + new String(encodedAuth);

                    //Get metadata json
                    DbMetadataJson dbMetadataJson = getMetadata(publishedBaseUrl + publishedVersionNo);
                    if (dbMetadataJson == null) {
                        return new Results(400, "There was an issue getting the published version.");
                    }

                    DbPrograms dbPrograms = dbMetadataJson.getMetadata();

                    List<DbIndicatorDetails> detailsList = new ArrayList<>();
//                    dbIndicatorDetailsList.add(indicatorDetails);
//                    detailsList.addAll(dbIndicatorDetailsList);

                    dbPrograms.setIndicatorDetails(detailsList);

                    dbMetadataJson.setMetadata(dbPrograms);

                    var response = GenericWebclient.putForSingleObjResponse(publishedBaseUrl + publishedVersionNo, dbMetadataJson, DbMetadataJson.class, Response.class);
                    if (response.getHttpStatusCode() == 200) {
                        return new Results(200, new DbDetails("The indicators values have been updated."));
                    }
                    return new Results(400, "There was an issue adding the resource");


                }

            }


        } catch (Exception e) {
            log.error("An error occurred while updating indicator descriptions");
        }

        return new Results(400, "There was an issue processing the request. Please try again.");
    }

    @Override
    public Results deleteDictionary(String uid) {

        try {

            String publishedBaseUrl = envUrlConstants.getDATA_STORE_ENDPOINT();
            int publishedVersionNo = getVersions(publishedBaseUrl);

            //Get metadata json
            DbMetadataJson dbMetadataJson = getMetadata(publishedBaseUrl + publishedVersionNo);
            if (dbMetadataJson == null) {
                return new Results(400, "There was an issue getting the published version.");
            }
            DbPrograms dbPrograms = dbMetadataJson.getMetadata();
            List<DbIndicatorDetails> dbIndicatorDetailsList = dbPrograms.getIndicatorDetails();

            if (dbIndicatorDetailsList != null) {

                for (DbIndicatorDetails dbIndicatorDetails : dbIndicatorDetailsList) {
                    String uuid = (String) dbIndicatorDetails.getUuid();
                    if (uid.equals(uuid)) {
                        dbIndicatorDetailsList.remove(dbIndicatorDetails);
                        break;
                    }
                }
                dbPrograms.setIndicatorDetails(dbIndicatorDetailsList);

                dbMetadataJson.setMetadata(dbPrograms);

                String auth = username + ":" + password;
                byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                String authHeader = "Basic " + new String(encodedAuth);

                var response = GenericWebclient.putForSingleObjResponseWithAuth(publishedBaseUrl + publishedVersionNo, dbMetadataJson, DbMetadataJson.class, Response.class, authHeader);

                if (response.getHttpStatusCode() == 200) {
                    return new Results(200, new DbDetails("The indicator has been deleted."));
                }
                return new Results(400, "There was an issue deleting the resource");


            }


        } catch (Exception e) {
            log.error("An error occurred while deleting the entry from the data dictionary");
        }


        return null;
    }

    @Override
    public Results getTopics() {

        String[] myTopics = {"Selection", "Procurement", "Distribution", "Use", "Coordination and leadership", "Pharmaceutical Laws and Regulations", "Ethics, Transparency, and Accountability", "Inspection and Enforcement", "Product Assessment and Registration", "Quality and Safety Surveillance", "Innovation, Research & Development", "Intellectual Property & Trade", "Costing & Pricing", "Financial Risk Protection", "Expenditure Tracking & Monitoring", "Human Resource Development ", "Human Resource Management", "Information Policy and Data Standardization"};
        List<String> topicList = new ArrayList<>(Arrays.asList(myTopics));
        DbResults dbResults1 = new DbResults(topicList.size(), topicList);

        String[] dropDowns = {IndicatorDropDowns.TEXT.name(), IndicatorDropDowns.SELECTION.name(), IndicatorDropDowns.NUMBER.name(),};
        List<String> dropList = new ArrayList<>(Arrays.asList(dropDowns));
        DbResults dbResults2 = new DbResults(dropList.size(), dropList);


        DbIndicatorTypes dbIndicatorTypes = new DbIndicatorTypes(dbResults1, dbResults2);
        return new Results(200, dbIndicatorTypes);
    }

    private int getVersions(String url) {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + new String(encodedAuth);

        var response = WebClient.builder().baseUrl(url).defaultHeader(HttpHeaders.AUTHORIZATION, authHeader).build().get().retrieve().bodyToMono(List.class).block();
        if (!response.isEmpty()) {
            return formatterClass.getNextVersion(response);
        } else {
            return 1;
        }
    }

    private DbMetadataJson getMetadata(String publishedBaseUrl) {

        try {

            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth);

            DbMetadataJson dbMetadataJson = WebClient.builder().baseUrl(publishedBaseUrl).defaultHeader(HttpHeaders.AUTHORIZATION, authHeader).build().get().retrieve().bodyToMono(DbMetadataJson.class).block();

            if (dbMetadataJson != null) {
                return dbMetadataJson;
            }

        } catch (Exception e) {
            log.error("An error occurred while fetching metadata from DHIS2 Datastore");
        }
        return null;
    }

}
