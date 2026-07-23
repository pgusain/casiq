package com.casiq.integration;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class GmailOAuthResourceTest {
    @Test
    void servesTheOAuthTestConsole() {
        given().when().get("/gmail/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Test the complete"));
    }

    @Test
    void authorizationStartsGooglePkceFlow() {
        given().when().post("/api/v1/gmail/authorize")
                .then().statusCode(200)
                .body("authorizationUrl", containsString("https://accounts.google.com/o/oauth2/v2/auth?"))
                .body("authorizationUrl", containsString("access_type=offline"))
                .body("authorizationUrl", containsString("code_challenge_method=S256"))
                .body("authorizationUrl", containsString("gmail.readonly"))
                .body("expiresAt", notNullValue());
    }

    @Test
    void callbackRejectsUnknownStateBeforeCallingGoogle() {
        given().queryParam("state", "unknown").queryParam("code", "code")
                .accept("application/json")
                .when().get("/api/v1/gmail/callback")
                .then().statusCode(400);
    }
}
