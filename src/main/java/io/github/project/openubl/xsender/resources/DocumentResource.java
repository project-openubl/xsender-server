package io.github.project.openubl.xsender.resources;

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

import io.github.project.openubl.xsender.events.DocumentEvent;
import io.github.project.openubl.xsender.events.DocumentEventManager;
import io.github.project.openubl.xsender.exceptions.StorageException;
import io.github.project.openubl.xsender.files.FilesManager;
import io.github.project.openubl.xsender.idm.DocumentRepresentation;
import io.github.project.openubl.xsender.idm.ErrorRepresentation;
import io.github.project.openubl.xsender.idm.PageRepresentation;
import io.github.project.openubl.xsender.managers.DocumentsManager;
import io.github.project.openubl.xsender.models.DocumentFilterModel;
import io.github.project.openubl.xsender.models.PageBean;
import io.github.project.openubl.xsender.models.PageModel;
import io.github.project.openubl.xsender.models.SortBean;
import io.github.project.openubl.xsender.models.jpa.NamespaceRepository;
import io.github.project.openubl.xsender.models.jpa.UBLDocumentRepository;
import io.github.project.openubl.xsender.models.jpa.entities.NamespaceEntity;
import io.github.project.openubl.xsender.models.jpa.entities.UBLDocumentEntity;
import io.github.project.openubl.xsender.models.utils.EntityToRepresentation;
import io.github.project.openubl.xsender.resources.utils.ResourceUtils;
import io.github.project.openubl.xsender.security.UserIdentity;
import io.github.project.openubl.xsender.sender.MessageSenderManager;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.NotSupportedException;
import javax.transaction.*;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Path("/namespaces/{namespaceId}/documents")
@Produces("application/json")
@Consumes("application/json")
@Transactional
@ApplicationScoped
public class DocumentResource {

    private static final Logger LOG = Logger.getLogger(DocumentResource.class);

    @Inject
    UserTransaction transaction;

    @Inject
    UserIdentity userIdentity;

    @Inject
    FilesManager filesManager;

    @Inject
    MessageSenderManager messageSenderManager;

    @Inject
    DocumentsManager documentsManager;

    @Inject
    DocumentEventManager documentEventManager;

    @Inject
    NamespaceRepository namespaceRepository;

    @Inject
    UBLDocumentRepository documentRepository;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadXML(
            @PathParam("namespaceId") @NotNull String namespaceId,
            MultipartFormDataInput input
    ) {
        DocumentEvent documentEvent;
        DocumentRepresentation documentRepresentation;

        try {
            transaction.begin();

            // Get namespace
            NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);

            // Extract file
            Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
            List<InputPart> fileInputParts = uploadForm.get("file");
            if (fileInputParts == null) {
                ErrorRepresentation error = new ErrorRepresentation("Form[file] is required");
                return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
            }

            byte[] xmlFile = null;
            try {
                for (InputPart inputPart : fileInputParts) {
                    InputStream fileInputStream = inputPart.getBody(InputStream.class, null);
                    xmlFile = IOUtils.toByteArray(fileInputStream);
                }
            } catch (IOException e) {
                throw new BadRequestException("Could not extract required data from upload/form");
            }

            if (xmlFile == null || xmlFile.length == 0) {
                ErrorRepresentation error = new ErrorRepresentation("Form[file] is empty");
                return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
            }

            UBLDocumentEntity documentEntity;
            try {
                documentEntity = documentsManager.createDocument(namespaceEntity, xmlFile);
                documentRepresentation = EntityToRepresentation.toRepresentation(documentEntity);
                documentEvent = new DocumentEvent(documentEntity.getId(), namespaceEntity.getId());
            } catch (StorageException e) {
                LOG.error(e);
                ErrorRepresentation error = new ErrorRepresentation(e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
            }

            // Commit transaction
            transaction.commit();
        } catch (NotSupportedException | SystemException | HeuristicRollbackException | HeuristicMixedException | RollbackException e) {
            LOG.error(e);
            try {
                transaction.rollback();
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            } catch (SystemException systemException) {
                LOG.error(systemException);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }

        // Event: new document has been created
        documentEventManager.fire(documentEvent);

        // Send file
        String documentId = documentRepresentation.getId();
        Message<String> message = Message.of(documentId)
                .withNack(throwable -> messageSenderManager.handleDocumentMessageError(documentId, throwable));
        messageSenderManager.sendToDocumentQueue(message);

        // Return result
        return Response.status(Response.Status.OK)
                .entity(documentRepresentation)
                .build();
    }

    @GET
    @Path("/")
    public PageRepresentation<DocumentRepresentation> getDocuments(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @QueryParam("ruc") List<String> ruc,
            @QueryParam("documentType") List<String> documentType,
            @QueryParam("filterText") String filterText,
            @QueryParam("offset") @DefaultValue("0") Integer offset,
            @QueryParam("limit") @DefaultValue("10") Integer limit,
            @QueryParam("sort_by") @DefaultValue("createdOn:desc") List<String> sortBy
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);

        PageBean pageBean = ResourceUtils.getPageBean(offset, limit);
        List<SortBean> sortBeans = ResourceUtils.getSortBeans(sortBy, UBLDocumentRepository.SORT_BY_FIELDS);

        DocumentFilterModel filters = DocumentFilterModel.DocumentFilterModelBuilder.aDocumentFilterModel()
                .withRuc(ruc)
                .withDocumentType(documentType)
                .build();

        PageModel<UBLDocumentEntity> pageModel;
        if (filterText != null && !filterText.trim().isEmpty()) {
            pageModel = documentRepository.list(namespaceEntity, filterText, filters, pageBean, sortBeans);
        } else {
            pageModel = documentRepository.list(namespaceEntity, filters, pageBean, sortBeans);
        }

        return EntityToRepresentation.toRepresentation(pageModel, EntityToRepresentation::toRepresentation);
    }


    @GET
    @Path("/{documentId}")
    public DocumentRepresentation getDocument(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
        UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

        return EntityToRepresentation.toRepresentation(documentEntity);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    @POST
    @Path("/{documentId}/retry-send")
    public Response resend(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        try {
            transaction.begin();

            NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
            UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

            documentEntity.setInProgress(true);
            documentEntity.setError(null);
            documentEntity.setScheduledDelivery(null);

            // Commit transaction
            transaction.commit();
        } catch (NotSupportedException | SystemException | HeuristicRollbackException | HeuristicMixedException | RollbackException e) {
            LOG.error(e);
            try {
                transaction.rollback();
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            } catch (SystemException systemException) {
                LOG.error(systemException);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        }

        // Event: new document has been created
        documentEventManager.fire(new DocumentEvent(documentId, namespaceId));

        // Send file
        Message<String> message = Message.of(documentId)
                .withNack(throwable -> messageSenderManager.handleDocumentMessageError(documentId, throwable));
        messageSenderManager.sendToDocumentQueue(message);

        return Response.status(Response.Status.OK)
                .build();
    }

    @GET
    @Path("/{documentId}/file")
    @Produces({MediaType.TEXT_XML, MediaType.APPLICATION_OCTET_STREAM})
    public Response getDocumentFile(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
        UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

        byte[] file = filesManager.getFileAsBytesAfterUnzip(documentEntity.getStorageFile());
        return Response.ok(file, MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documentEntity.getDocumentID() + ".xml" + "\"")
                .build();
    }

    @GET
    @Path("/{documentId}/file-link")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDocumentFileLink(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
        UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

        String fileLink = filesManager.getFileLink(documentEntity.getStorageFile());
        return Response.ok(fileLink)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documentEntity.getDocumentID() + ".xml" + "\"")
                .build();
    }

    @GET
    @Path("/{documentId}/cdr")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getDocumentCdr(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
        UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

        if (documentEntity.getStorageCdr() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        byte[] file = filesManager.getFileAsBytesWithoutUnzipping(documentEntity.getStorageCdr());
        return Response.ok(file, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documentEntity.getDocumentID() + ".zip" + "\"")
                .build();
    }

    @GET
    @Path("/{documentId}/cdr-link")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDocumentCdrLink(
            @PathParam("namespaceId") @NotNull String namespaceId,
            @PathParam("documentId") @NotNull String documentId
    ) {
        NamespaceEntity namespaceEntity = namespaceRepository.findByIdAndOwner(namespaceId, userIdentity.getUsername()).orElseThrow(NotFoundException::new);
        UBLDocumentEntity documentEntity = documentRepository.findById(namespaceEntity, documentId).orElseThrow(NotFoundException::new);

        if (documentEntity.getStorageCdr() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String fileLink = filesManager.getFileLink(documentEntity.getStorageCdr());
        return Response.ok(fileLink)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documentEntity.getDocumentID() + ".zip" + "\"")
                .build();
    }

}


