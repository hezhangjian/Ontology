package com.hezhangjian.ontology.controller;

import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.OBJECT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hezhangjian.ontology.model.ResourceDraftRequest;
import com.hezhangjian.ontology.model.ResourceIdentityRequest;
import com.hezhangjian.ontology.model.ResourceView;
import com.hezhangjian.ontology.service.ModelingApiService;
import com.hezhangjian.ontology.service.ModelingApiService.VersionedResource;
import com.hezhangjian.ontology.service.ObjectInstanceImportService;
import com.hezhangjian.ontology.service.ObjectInstanceSchemaService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ObjectTypeControllerTest {
    private static final String ONTOLOGY_ID = "token_api";
    private static final String OBJECT_TYPE_ID = "employee";
    private final ModelingApiService service = mock(ModelingApiService.class);
    private final ObjectTypeController controller = new ObjectTypeController(
            service,
            mock(ObjectInstanceSchemaService.class),
            mock(ObjectInstanceImportService.class));

    @Test
    void createsCompleteObjectTypeThroughUnifiedContract() {
        ResourceDraftRequest request = new ResourceDraftRequest();
        ResourceView view = objectType();
        when(service.create(ONTOLOGY_ID, OBJECT_TYPE, request))
                .thenReturn(new VersionedResource(view, 1));

        var response = controller.createObjectType(ONTOLOGY_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(response.getHeaders().getLocation())
                .hasPath("/v1/ontologies/token_api/object-types/employee");
        assertThat(response.getBody()).isSameAs(view);
    }

    @Test
    void supportsCompleteObjectTypeCrudThroughUnifiedContract() {
        ResourceView view = objectType();
        ResourceIdentityRequest request = new ResourceIdentityRequest();
        when(service.list(ONTOLOGY_ID, OBJECT_TYPE, "emp")).thenReturn(List.of(view));
        when(service.get(ONTOLOGY_ID, OBJECT_TYPE_ID, OBJECT_TYPE))
                .thenReturn(new VersionedResource(view, 1));
        when(service.updateIdentity(
                        ONTOLOGY_ID, OBJECT_TYPE_ID, OBJECT_TYPE, request))
                .thenReturn(new VersionedResource(view, 2));

        assertThat(controller.listObjectTypes(ONTOLOGY_ID, "emp").getBody())
                .containsExactly(view);
        assertThat(controller.getObjectType(OBJECT_TYPE_ID, ONTOLOGY_ID).getBody())
                .isSameAs(view);
        assertThat(controller.updateObjectType(
                                OBJECT_TYPE_ID, ONTOLOGY_ID, request)
                        .getHeaders()
                        .getETag())
                .isEqualTo("\"2\"");
        assertThat(controller.deleteObjectType(
                                OBJECT_TYPE_ID, ONTOLOGY_ID)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(ONTOLOGY_ID, OBJECT_TYPE_ID, OBJECT_TYPE);
    }

    private ResourceView objectType() {
        return new ResourceView()
                .id(OBJECT_TYPE_ID)
                .resourceId(UUID.randomUUID())
                .kind(ResourceView.KindEnum.OBJECT_TYPE)
                .displayName("人员");
    }
}
