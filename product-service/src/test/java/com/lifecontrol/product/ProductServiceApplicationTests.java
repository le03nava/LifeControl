package com.lifecontrol.product;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.apache.http.HttpStatus;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

    @Test
    void shouldReturnPaginatedProducts() {
        // Create 3 products first
        for (int i = 1; i <= 3; i++) {
            String body = """
                    { "name": "Product %d", "description": "Description %d", "price": %d }
                    """.formatted(i, i, i * 10);
            RestAssured.given()
                    .port(port)
                    .body(body)
                    .contentType("application/json")
                    .post("/api/products");
        }

        // Fetch page 0 with size 2
        RestAssured.given()
                .port(port)
                .param("page", 0)
                .param("size", 2)
                .when()
                .get("/api/products")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("totalElements", Matchers.greaterThanOrEqualTo(3))
                .body("number", Matchers.equalTo(0))
                .body("size", Matchers.equalTo(2))
                .body("content", Matchers.hasSize(2))
                .body("totalPages", Matchers.greaterThanOrEqualTo(2));
    }

    @Test
    void shouldSearchProductsByName() {
        RestAssured.given()
                .port(port)
                .param("search", "iPhone")
                .param("page", 0)
                .param("size", 10)
                .when()
                .get("/api/products")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("totalElements", Matchers.greaterThanOrEqualTo(1))
                .body("content[0].name", Matchers.containsString("iPhone"));
    }

    @Test
    void shouldReturnEmptyPageForNoMatchSearch() {
        RestAssured.given()
                .port(port)
                .param("search", "zzzzz_nonexistent_xxxxx")
                .param("page", 0)
                .param("size", 10)
                .when()
                .get("/api/products")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("content", Matchers.hasSize(0))
                .body("totalElements", Matchers.equalTo(0));
    }

    @Test
    void shouldUseDefaultPageValues() {
        RestAssured.given()
                .port(port)
                .when()
                .get("/api/products")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("number", Matchers.equalTo(0))
                .body("size", Matchers.equalTo(12)); // @PageableDefault(size = 12)
    }

}
