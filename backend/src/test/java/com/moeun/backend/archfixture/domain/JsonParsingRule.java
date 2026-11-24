package com.moeun.backend.archfixture.domain;

import tools.jackson.databind.JsonNode;

/** 위반 표본: domain이 Jackson으로 JSON을 해석한다. */
public class JsonParsingRule {

    public int readAmount(JsonNode body) {
        return body.path("amount").asInt();
    }
}
