package com.lifecontrol.product;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.restdocs.restassured.RestAssuredRestDocumentation;
import org.testcontainers.containers.PostgreSQLContainer;

//@TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestDocs
class ProductServiceApplicationTests {

    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.2");

    @LocalServerPort
    private Integer port;

    @Autowired
    private RequestSpecification documentationSpec;

    @BeforeEach
    void setup() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    static {
        postgreSQLContainer.start();
    }

    @Test
    void shouldCreateProduct() {
        String requestBody =
                """
                {
                	"name": "iPhone 15",
                	"description": "iPhone 15",
                	"price": 100
                }
                """;

        RestAssured.given(documentationSpec)
                .port(port)
                .filter(RestAssuredRestDocumentation.document("save-note-created"))
                .body(requestBody)
                .contentType("application/json")
                .when()
                .post("/api/products")
                .then()
                .statusCode(HttpStatus.SC_CREATED);
        /*
                .body("id", Matchers.notNullValue())
                .body("name", Matchers.equalTo("iPhone 15"))
                .body("description", Matchers.equalTo("iPhone 15"))
                .body("price", Matchers.equalTo(100));*/

    }

}
