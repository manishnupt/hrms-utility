package com.hrms.hrms_utility.service;

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
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
        ActionItem actionItem= ActionItemHelper.toEntity(req);
        return actionItemRepo.save(actionItem);
    }

    public List<ActionItemResponse> getAssignedItems(String userId, ActionItem.ActionStatus status) {

        List<ActionItem> actionItem;
        if (status != null)
            actionItem = actionItemRepo.findByAssigneeUserIdAndStatus(userId, status);
        else
            actionItem = actionItemRepo.findByAssigneeUserId(userId);

        return mapToResponse(actionItem);

    }

    public List<ActionItemResponse> getInitiatedItems(String userId, ActionItem.ActionStatus status) {
        List<ActionItem> actionItem;
        if (status != null)
            actionItem = actionItemRepo.findByInitiatorUserIdAndStatus(userId, status);
        else
            actionItem = actionItemRepo.findByInitiatorUserId(userId);

        return mapToResponse(actionItem);
    }

    public ActionItem getActionItemById(Long id) {
        Optional<ActionItem> byId = actionItemRepo.findById(id);
        if(byId.isPresent()){
            return byId.get();
        }
        else{
            throw new EntityNotFoundException("Action item not found with id "+id);
        }
    }

    public ActionItem updateStatus(Long id, ActionItem.ActionStatus status, String remarks) {
        ActionItem item = actionItemRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ActionItem not found with id " + id));

        String url = employeeServiceUrl + "/employee";
        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

        // Create the request entity
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<?> response = null;
        if (item.getType().equals(ActionItem.ActionType.LEAVE)) {
            url+="/leave-balance/";
            response = restTemplate.postForEntity(
                    url + item.getAssigneeUserId() + "/deduct/" + item.getReferenceId(),
                    entity,
                    String.class);
        } else if (item.getType().equals(ActionItem.ActionType.WFH)) {
            url+="/wfh-balance/";
            response = restTemplate.postForEntity(
                    url + item.getAssigneeUserId() + "/deduct/" + item.getReferenceId(),
                    entity,
                    String.class);

        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to deduct item with id: {}", id);
        }
        item.setStatus(status);
        item.setRemarks(remarks);
        item.setUpdatedAt(LocalDateTime.now());
        return actionItemRepo.save(item);
    }

    public void markAsSeen(Long id) {
        ActionItem item = actionItemRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ActionItem not found with id " + id));
        item.setSeen(true); // assuming you have a 'seen' boolean field
        item.setUpdatedAt(LocalDateTime.now());
        actionItemRepo.save(item);
    }

    public void deleteActionItem(Long id) {
        if (!actionItemRepo.existsById(id)) {
            throw new EntityNotFoundException("ActionItem not found with id " + id);
        }
        actionItemRepo.deleteById(id);
    }


    public List<ActionItem> getByReferenceId(Long referenceId) {
        return actionItemRepo.findByReferenceId(referenceId);
    }

    public Long getPendingCount(String assigneeUserId) {
        return actionItemRepo.countByAssigneeUserIdAndStatus(
                assigneeUserId,
                ActionItem.ActionStatus.PENDING
        );
    }

    public List<ActionItem> getByReferenceIdAndType(Long referenceId, ActionItem.ActionType type) {
        return actionItemRepo.findByReferenceIdAndType(referenceId, type);
    }

    public List<ActionItem> getByReferenceIdAndStatus(Long referenceId, ActionItem.ActionStatus status) {
        return actionItemRepo.findByReferenceIdAndStatus(referenceId, status);
    }

    public EmployeeDto callExternalService(String type, String userId) {
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

    public BaseDto callExternalService(String type, String userId, Long referenceId) {
        log.info("calling external service for type :{}",type);
        String url = employeeServiceUrl + "/employees/" + userId + "/";
        Class<? extends BaseDto> responseType;

        if ("LEAVE".equals(type)) {
            url += "leave-tracker/" + referenceId;
            responseType = LeaveDto.class;
        } else if ("TIMESHEET".equals(type)) {
            url += "timesheets/" + referenceId;
            responseType = TimesheetDto.class;
        }
         else if ("WFH".equals(type)) {
             log.info("calling WFH service for referenceId :{}",referenceId);
            url += "/wfh/" + referenceId;
            log.info("constructed url :{}",url);
            responseType = WorkFromHomeDto.class;
         }
         //else if ("EXPENSE".equals(type)) {
        //     url += "expense/" + referenceId;
        //     responseType = ExpenseDto.class;
        // } else if ("ASSET_REQUEST".equals(type)) {
        //     url += "asset_request/" + referenceId;
        //     responseType = AssetRequestDto.class;
        // }
        else {
            responseType = BaseDto.class;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<? extends BaseDto> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                responseType
        );

        return response.getBody();
    }


    private List<ActionItemResponse> mapToResponse(List<ActionItem> actionItems) {
        return actionItems.stream()
                .map(item -> ActionItemResponse.builder()
                        .id(item.getId())
                        .title(item.getTitle())
                        .description(item.getDescription())
                        .remarks(item.getRemarks())
                        .seen(item.isSeen())
                        .type(item.getType().toString())
                        .status(item.getStatus().toString())
                        .initiatorUser(
                                callExternalService("employee", item.getInitiatorUserId()))
                        .assigneeUser(
                                callExternalService("employee", item.getAssigneeUserId()))
                        .reference(
                                callExternalService(item.getType().toString(), item.getInitiatorUserId(),
                                        item.getReferenceId()))
                        .createdAt(item.getCreatedAt())
                        .updatedAt(item.getUpdatedAt())
                        .build())
                .toList();
    }
}
