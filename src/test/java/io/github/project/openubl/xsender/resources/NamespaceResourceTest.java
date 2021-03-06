/*
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Eclipse Public License - v 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.project.openubl.xsender.resources;

import io.github.project.openubl.xsender.models.jpa.NamespaceRepository;
import io.github.project.openubl.xsender.models.jpa.entities.NamespaceEntity;
import io.github.project.openubl.xsender.resources.config.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@QuarkusTestResource(KeycloakServer.class)
@QuarkusTestResource(PostgreSQLServer.class)
@QuarkusTestResource(StorageServer.class)
@QuarkusTestResource(SenderServer.class)
public class NamespaceResourceTest extends BaseKeycloakTest {

    @Inject
    NamespaceRepository namespaceRepository;

    @BeforeEach
    public void beforeEach() {
        namespaceRepository.deleteAll();
    }

    @Test
    public void getNamespace() {
        // Given

        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName("my-namespace")
                .withOwner("alice")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        given().auth().oauth2(getAccessToken("alice"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(200)
                .body("name", is(namespace.getName()),
                        "description", is(namespace.getDescription())
                );

        // Then

    }

    @Test
    public void getNamespaceByNotOwner_shouldReturnNotFound() {
        // Given

        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName("my-namespace1")
                .withOwner("admin")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        given().auth().oauth2(getAccessToken("alice"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(404);

        given().auth().oauth2(getAccessToken("admin"))
                .header("Content-Type", "application/json")
                .when()
                .get("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(200);

        // Then
    }

    @Test
    public void updateNamespace() {
        // Given
        String NAME = "my-namespace";

        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName(NAME)
                .withOwner("admin")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        NamespaceEntity namespaceUpdate = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withName("new name")
                .withDescription("my description")
                .build();

        given().auth().oauth2(getAccessToken("admin"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(namespaceUpdate)
                .when()
                .put("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(200)
                .body("name", is(namespaceUpdate.getName()),
                        "description", is(namespaceUpdate.getDescription())
                );

        // Then
    }

    @Test
    public void updateNamespaceByNotOwner_shouldNotBeAllowed() {
        // Given
        String NAME = "my-namespace";

        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName(NAME)
                .withOwner("admin")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        NamespaceEntity namespaceUpdate = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withDescription("my description")
                .build();

        given().auth().oauth2(getAccessToken("alice"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(namespaceUpdate)
                .when()
                .put("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(404);

        given().auth().oauth2(getAccessToken("admin"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(namespaceUpdate)
                .when()
                .put("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(200);

        // Then
    }

    @Test
    public void deleteNamespace() {
        // Given
        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName("my-namespace")
                .withOwner("alice")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        given().auth().oauth2(getAccessToken("alice"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .when()
                .delete("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(204);

        // Then
    }

    @Test
    public void deleteNamespaceByNotOwner_shouldNotBeAllowed() {
        // Given
        NamespaceEntity namespace = NamespaceEntity.NamespaceEntityBuilder.aNamespaceEntity()
                .withId(UUID.randomUUID().toString())
                .withName("my-namespace")
                .withOwner("admin")
                .withCreatedOn(new Date())
                .build();
        namespaceRepository.persist(namespace);

        // When
        given().auth().oauth2(getAccessToken("alice"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .when()
                .delete("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(404);

        given().auth().oauth2(getAccessToken("admin"))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .when()
                .delete("/api/namespaces/" + namespace.getId())
                .then()
                .statusCode(204);

        // Then
    }

}
