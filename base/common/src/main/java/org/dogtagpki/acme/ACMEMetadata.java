//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.acme;

import java.util.Properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Endi S. Dewata
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown=true)
public class ACMEMetadata {

    private String termsOfService;
    private String website;
    private String[] caaIdentities;
    private Boolean externalAccountRequired;

    public String getTermsOfService() {
        return termsOfService;
    }

    public void setTermsOfService(String termsOfService) {
        this.termsOfService = termsOfService;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String[] getCaaIdentities() {
        return caaIdentities;
    }

    public void setCaaIdentities(String[] caaIdentities) {
        this.caaIdentities = caaIdentities;
    }

    public Boolean getExternalAccountRequired() {
        return externalAccountRequired;
    }

    public void setExternalAccountRequired(Boolean externalAccountRequired) {
        this.externalAccountRequired = externalAccountRequired;
    }

    public String toJSON() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(this);
    }

    public static ACMEMetadata fromJSON(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, ACMEMetadata.class);
    }

    public static ACMEMetadata fromProperties(Properties props) throws Exception {

        ACMEMetadata metadata = new ACMEMetadata();
        metadata.setTermsOfService(props.getProperty("termsOfService"));
        metadata.setWebsite(props.getProperty("website"));

        // split caaIdentities by commas
        String[] caaIdentities = props.getProperty("caaIdentities", "").split("\\s*,\\s*");
        metadata.setCaaIdentities(caaIdentities);

        String externalAccountRequired = props.getProperty("externalAccountRequired");
        metadata.setExternalAccountRequired(externalAccountRequired == null ? null : Boolean.valueOf(externalAccountRequired));

        return metadata;
    }

    public String toString() {
        try {
            return toJSON();
        } catch (Exception e) {
            return super.toString();
        }
    }
}
