package com.hrms.hrms_utility.service;

import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.repository.ActionItemRepo;
import com.hrms.hrms_utility.request.ActionItemRequest;
import com.hrms.hrms_utility.response.ActionItemResponse;
import com.hrms.hrms_utility.response.BaseDto;
import com.hrms.hrms_utility.response.EmployeeDto;
import com.hrms.hrms_utility.response.LeaveDto;
import com.hrms.hrms_utility.response.TimesheetDto;
import com.hrms.hrms_utility.utility.ActionItemHelper;
import com.hrms.hrms_utility.utility.TenantContext;
import jakarta.persistence.EntityNotFoundException;
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
        headers.set("x-tenant-id", TenantContext.getCurrentTenant());

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
        String url = employeeServiceUrl + "/employees/" + userId + "/";
        Class<? extends BaseDto> responseType;
        if (type.equals("LEAVE")) {
            url += "leave-tracker/" + referenceId;
            responseType = LeaveDto.class;
        } else if (type.equals("TIMESHEET")) {
            url += "timesheets/" + referenceId;
            responseType = TimesheetDto.class;
        }
        // else if(type.equals("WFH")) {
        // url += "wfh/"+ referenceId;
        // }
        // else if(type.equals("EXPENSE")) {
        // url += "expense/"+ referenceId;
        // } else if(type.equals("ASSET_REQUEST")) {
        // url += "asset_request/" + referenceId;
        // }
        else {
            responseType = BaseDto.class;
        }
        return restTemplate.getForEntity(url, responseType).getBody();
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
                                callExternalService(item.getType().toString(), item.getAssigneeUserId(),
                                        item.getReferenceId()))
                        .createdAt(item.getCreatedAt())
                        .updatedAt(item.getUpdatedAt())
                        .build())
                .toList();
    }
}
