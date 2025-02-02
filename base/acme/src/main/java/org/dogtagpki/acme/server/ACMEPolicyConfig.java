//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.acme.server;

import java.util.Map.Entry;
import java.util.Properties;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class includes mechanisms to enforce various policy and security
 * restrictions explicitly or implicitly enumerated by ACME.
 */
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown=true)
public class ACMEPolicyConfig {

    @JsonProperty("wildcard")
    private Boolean enableWildcardIssuance = true;

    @JsonProperty("retention")
    private ACMERetentionConfig retention = new ACMERetentionConfig();

    public ACMEPolicyConfig() {}

    @JsonIgnore
    public boolean getEnableWildcards() {
        return enableWildcardIssuance;
    }

    public void setEnableWildcards(boolean on) {
        enableWildcardIssuance = on;
    }

    public ACMERetentionConfig getRetention() {
        return retention;
    }

    public void setRetention(ACMERetentionConfig retentionPolicy) {
        this.retention = retentionPolicy;
    }

    public void setProperty(String key, String value) throws Exception {

        if (key.equals("wildcard")) {
            enableWildcardIssuance = Boolean.valueOf(value);
            return;
        }

        if (key.startsWith("retention.")) {
            String retentionKey = key.substring(10);
            retention.setProperty(retentionKey, value);
        }
    }

    public String toJSON() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(this);
    }

    public static ACMEPolicyConfig fromJSON(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, ACMEPolicyConfig.class);
    }

    public static ACMEPolicyConfig fromProperties(Properties props) throws Exception {

        ACMEPolicyConfig config = new ACMEPolicyConfig();

        for (Entry<Object, Object> entry : props.entrySet()) {
            String key = entry.getKey().toString();
            String value = entry.getValue().toString();
            config.setProperty(key, value);
        }

        return config;
    }

    public String toString() {
        try {
            return toJSON();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        ACMEPolicyConfig config = new ACMEPolicyConfig();
        System.out.println(config);
    }
}
