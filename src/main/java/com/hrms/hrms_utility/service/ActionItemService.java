package com.hrms.hrms_utility.service;

import com.hrms.hrms_utility.request.ExternalServiceResponse;
import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.repository.ActionItemRepo;
import com.hrms.hrms_utility.request.ActionItemRequest;
import com.hrms.hrms_utility.response.*;
import com.hrms.hrms_utility.utility.ActionItemHelper;
import com.hrms.hrms_utility.utility.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.hrms.hrms_utility.utility.JwtUtil;

@Service
@Log4j2
public class ActionItemService {

    @Autowired
    ActionItemRepo actionItemRepo;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${employee.service.url}")
    private String employeeServiceUrl;

    public ActionItem createActionItem(ActionItemRequest req) {
        log.info("Creating action item for assignee: {}", req.getAssigneeUserId());
        ActionItem actionItem= ActionItemHelper.toEntity(req);
        return actionItemRepo.save(actionItem);
    }

    public List<ActionItemResponse> getAssignedItems(String userId, ActionItem.ActionStatus status) {

        log.info("Getting assigned items for userId: {}, status: {}", userId, status);
        List<ActionItem> actionItem;
        if (status != null)
            actionItem = actionItemRepo.findByAssigneeUserIdAndStatus(userId, status);
        else
            actionItem = actionItemRepo.findByAssigneeUserId(userId);

        return mapToResponse(actionItem);

    }

    public List<ActionItemResponse> getInitiatedItems(String userId, ActionItem.ActionStatus status) {
        log.info("Getting initiated items for userId: {}, status: {}", userId, status);
        List<ActionItem> actionItem;
        if (status != null)
            actionItem = actionItemRepo.findByInitiatorUserIdAndStatus(userId, status);
        else
            actionItem = actionItemRepo.findByInitiatorUserId(userId);

        return mapToResponse(actionItem);
    }

    public ActionItem getActionItemById(Long id) {
        log.info("Getting action item by id: {}", id);
        Optional<ActionItem> byId = actionItemRepo.findById(id);
        if(byId.isPresent()){
            return byId.get();
        }
        else{
            throw new EntityNotFoundException("Action item not found with id "+id);
        }
    }

    public ActionItem updateStatus(
            Long id,
            ActionItem.ActionStatus status,
            String remarks, String token) {

        log.info("Updating status of action item id: {} to {}", id, status);

        ActionItem item = actionItemRepo.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "ActionItem not found with id " + id));

        String extractedUserId=JwtUtil.extractUserId(token);

        if(!extractedUserId.equals(item.getAssigneeUserId())) {
            log.warn("Unauthorized status update attempt by userId: {} for action item id: {}", extractedUserId, id);
            throw new SecurityException("Unauthorized access");
        }

        if (ActionItem.ActionType.LEAVE.equals(item.getType())
                || ActionItem.ActionType.WFH.equals(item.getType())
                || ActionItem.ActionType.TIMESHEET.equals(item.getType())) {
            changeExternalStatus(item, status);
        }

        item.setStatus(status);
        item.setRemarks(remarks);
        item.setUpdatedAt(LocalDateTime.now());

        return actionItemRepo.save(item);
    }

    private void changeExternalStatus(ActionItem item, ActionItem.ActionStatus status) {
        String url;
        if (ActionItem.ActionType.LEAVE.equals(item.getType())) {
            url = employeeServiceUrl + "/employee/" + item.getInitiatorUserId() + "/leave-tracker/" + item.getReferenceId() + "/status";
        } else if (ActionItem.ActionType.WFH.equals(item.getType())) {
            url = employeeServiceUrl + "/employee/" + item.getInitiatorUserId() + "/wfh-tracker/" + item.getReferenceId() + "/status";
        } else if (ActionItem.ActionType.TIMESHEET.equals(item.getType())) {
            url = employeeServiceUrl + "/employees/" + item.getInitiatorUserId() + "/timesheets/" + item.getReferenceId() + "/status";
        } else {
            log.warn("No external status update needed for action item type: {}", item.getType());
            return;
        }

        url = url + "?status=" + status;

        log.info("calling api to change the status {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error(
                        "Failed to change status for action item id: {}, status: {}",
                        item.getId(),
                        response.getStatusCode()
                );
                throw new RuntimeException(
                        "Status change failed with status "
                                + response.getStatusCode()
                );
            }

            log.info(
                    "Status change successful for action item id: {}",
                    item.getId()
            );

        } catch (RestClientException e) {
            log.error(
                    "Error while changing status for action item id: {}",
                    item.getId(),
                    e
            );
            throw new RuntimeException(
                    "Unable to change status", e
            );
        }
    }

    public void markAsSeen(Long id) {
        log.info("Marking action item id: {} as seen", id);
        ActionItem item = actionItemRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ActionItem not found with id " + id));
        item.setSeen(true); // assuming you have a 'seen' boolean field
        item.setUpdatedAt(LocalDateTime.now());
        actionItemRepo.save(item);
    }

    public void deleteActionItem(Long id) {
        log.info("Deleting action item with id: {}", id);
        if (!actionItemRepo.existsById(id)) {
            throw new EntityNotFoundException("ActionItem not found with id " + id);
        }
        actionItemRepo.deleteById(id);
    }


    public List<ActionItem> getByReferenceId(Long referenceId) {
        log.info("Getting action items by referenceId: {}", referenceId);
        return actionItemRepo.findByReferenceId(referenceId);
    }

    public Long getPendingCount(String assigneeUserId) {
        log.info("Getting pending count for assigneeUserId: {}", assigneeUserId);
        return actionItemRepo.countByAssigneeUserIdAndStatus(
                assigneeUserId,
                ActionItem.ActionStatus.PENDING
        );
    }

    public List<ActionItem> getByReferenceIdAndType(Long referenceId, ActionItem.ActionType type) {
        log.info("Getting action items by referenceId: {} and type: {}", referenceId, type);
        return actionItemRepo.findByReferenceIdAndType(referenceId, type);
    }

    public List<ActionItem> getByReferenceIdAndStatus(Long referenceId, ActionItem.ActionStatus status) {
        log.info("Getting action items by referenceId: {} and status: {}", referenceId, status);
        return actionItemRepo.findByReferenceIdAndStatus(referenceId, status);
    }

    public EmployeeDto callExternalService(String type, String userId) {
        log.info("Calling external service for type: {}, userId: {}", type, userId);
        String url = employeeServiceUrl + "/";
        if ("employee".equals(type)) {
            url += "employee/";
        }

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

        // Create the request entity
        HttpEntity<String> entity = new HttpEntity<>(headers);

        // Make the HTTP GET request with headers
        ResponseEntity<EmployeeDto> response = restTemplate.exchange(
                url + userId,
                HttpMethod.GET,
                entity,
                EmployeeDto.class
        );

        return response.getBody();
    }

    public ExternalServiceResponse callExternalService(
            String type,
            String userId,
            Long referenceId) {

        log.info("calling external service for type : {}", type);

        String baseUrl = employeeServiceUrl + "/employees/" + userId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ExternalServiceResponse responseWrapper = new ExternalServiceResponse();
        responseWrapper.setType(type);

        switch (type) {
            case "LEAVE" -> {
                String url = baseUrl + "/leave-tracker/" + referenceId;
                LeaveDto dto = restTemplate.exchange(
                        url, HttpMethod.GET, entity, LeaveDto.class
                ).getBody();
                responseWrapper.setLeave(dto);
            }

            case "TIMESHEET" -> {
                String url = baseUrl + "/timesheets/" + referenceId;
                TimesheetDto dto = restTemplate.exchange(
                        url, HttpMethod.GET, entity, TimesheetDto.class
                ).getBody();
                responseWrapper.setTimesheet(dto);
            }

            case "WFH" -> {
                String url = baseUrl + "/wfh/" + referenceId;
                WorkFromHomeDto dto = restTemplate.exchange(
                        url, HttpMethod.GET, entity, WorkFromHomeDto.class
                ).getBody();
                responseWrapper.setWfh(dto);
            }

            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        }

        return responseWrapper;
    }



    private List<ActionItemResponse> mapToResponse(List<ActionItem> actionItems) {

        return actionItems.stream()
                .map(item -> {
                    ActionItemResponse.ActionItemResponseBuilder builder =
                            ActionItemResponse.builder()
                                    .id(item.getId())
                                    .title(item.getTitle())
                                    .description(item.getDescription())
                                    .remarks(item.getRemarks())
                                    .seen(item.isSeen())
                                    .type(item.getType().toString())
                                    .status(item.getStatus().toString())
                                    .initiatorUser(
                                            callExternalService(
                                                    "employee",
                                                    item.getInitiatorUserId()))
                                    .assigneeUser(
                                            callExternalService(
                                                    "employee",
                                                    item.getAssigneeUserId()))
                                    .createdAt(item.getCreatedAt())
                                    .updatedAt(item.getUpdatedAt());

                    assignReference(builder, item);
                    return builder.build();
                })
                .toList();
    }


    private void assignReference(
            ActionItemResponse.ActionItemResponseBuilder builder,
            ActionItem item) {

        // Single external call
        ExternalServiceResponse response =
                callExternalService(
                        item.getType().toString(),
                        item.getInitiatorUserId(),
                        item.getReferenceId()
                );

        switch (item.getType()) {
            case LEAVE -> builder.leave(response.getLeave());
            case TIMESHEET -> builder.timesheet(response.getTimesheet());
            case WFH -> builder.wfh(response.getWfh());
            default -> log.warn("Unsupported reference type: {}", item.getType());
        }
    }


}
